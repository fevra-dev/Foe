# ADR-0011 — Redact Agent-Generated Reports at the Write Boundary

**Status:** Accepted · **Date:** 2026-06-12
**Supersedes:** — · **Superseded-by:** —
*(Grilled via `grill-with-threat-model` 2026-06-12 → 8 break paths found; mitigations folded below. Mined from Visa VVAH `report/redact.py`, Apache-2.0 — STACK.md §17.)*

## Context
The `/arch /attack /audit` loop (and BugHunter) writes **agent-generated reports** — `.audit/FINDINGS.md`, `.audit/AUDIT.md`, SARIF, PoC files — that **quote untrusted target source verbatim**. A frontier model routinely copies a credential, PAN, PII value, or private key it `Read` straight into a finding ("hardcoded key `…` at config.rs:12"). That value then lands on disk and can be committed, screen-shared, or pushed to a shared repo.

ADR-0004 already says *"secrets are never persisted to disk in plaintext"* — but it had **no enforcement on the agent's own output**; Trivy (ADR-0001) scans the *target repo* for secrets, not the *report* the agent writes about it. This is the output-side enforcement arm ADR-0004 was missing.

**Scope honesty (load-bearing — grill V1/V2):** this control is a **defense-in-depth backstop** that scrubs the *last, local copy* of an **announced** secret from the on-disk report. It is **NOT a containment boundary** for the operator's Solana/BTC keys: by write-time the secret has already entered the model's context and gone over the wire to the provider. Containment remains **ADR-0004** (don't read secret material into context in the first place) + **ADR-0007** (untrusted repos run in the egress-denied sandbox, so a read secret can't exfiltrate). ADR-0011 makes no claim about over-the-wire exposure.

## Decision
1. **Write-time first.** Every harness-generated report MUST be passed through `~/.claude/scripts/redact.py` **before** it is written to disk (the `/audit` and `/attack` write steps pipe through it). Secrets that are never written are never committed (closes grill V8).
2. **Gate as backstop.** A lint-gate stage runs `redact.py --check` on staged `.audit/**` (`*.md` + `*.sarif`) and **fails closed** if any unredacted card/PII/credential/key material remains.
3. **Redaction ≠ containment.** This ADR does not relax ADR-0004: the agent still must not pull secret files into context unnecessarily, and untrusted targets still run in the ADR-0007 sandbox. Redaction is the last line, not the only line.
4. **Documented residuals are explicit, not hidden.** Bare seed-phrase mnemonics, encoded/obfuscated secrets, and standalone base58/WIF keys are **out of the filter's precision envelope** and are handled by ADR-0004 + human review — not silently assumed covered.

## Considered Options
- **Deterministic write-boundary regex redaction (chosen)** — high-precision (Luhn+IIN PANs, keyword-gated secrets), deterministic, gateable in CI, lands in the thinnest 2×2 cell (computational-feedback). Mirrors VVAH's battle-tested filter.
- **LLM-based redaction** — rejected: non-deterministic (can't gate CI on it), and it re-feeds the secret to a model — the opposite of the goal.
- **No redaction, rely on ADR-0004 discipline alone** — rejected: the model verbatim-quotes regardless of discipline; a cheap deterministic backstop is warranted.
- **Aggressive standalone base58/hex key masking** — rejected: a 64-byte Solana *signature* and a *secret key* are both ~88-char base58; masking standalone base58 would mangle legitimate signatures/addresses in a Solana audit (grill V4). Crypto keys are caught keyword-gated instead; only the unambiguous `xprv`/`tprv` prefix is masked standalone.

## Consequences
Reports are scrubbed of announced card/PII/credential/key material before disk, with a fail-closed CI backstop. **Cost:** a finding *about* a hardcoded secret renders the value as `[REDACTED-SECRET]` — the reviewer reads the cited `file:line` in source for the cleartext (the location + CWE *is* the finding; the cleartext is not). Documented residuals (mnemonics, encoded secrets, bare base58 keys) remain the province of ADR-0004 + human review. The filter is pure-stdlib, no deps, no network (ADR-0001 clean).

## Security Considerations & Mitigations (grill-with-threat-model)
Mapped to the 8 break paths found (full list below):
- **V1 — operator's key formats not in the pattern set.** → Added `XPRV`/`tprv` standalone (zero-FP prefix) + `secret_key`/`private_key`/`signing_key`/`seed_phrase`/`mnemonic`/`keypair` to the keyword gate (previously only `api_key`/`access_key` were gated — `secret_key`/`private_key` slipped through). **Residual:** bare mnemonics and standalone base58/byte-array keys are *not* reliably catchable → ADR-0004 owns them (don't read seed material into context). Stated, not hidden.
- **V2 — secret already left at Read-time.** → Scope claim narrowed in Decision §3: redaction is the *last-copy backstop*, not containment. Containment = ADR-0004 (don't read) + ADR-0007 egress-denied sandbox. No false "secrets can't escape" assurance.
- **V3 — gate scoped too narrowly.** → Gate covers `.audit/**` `*.md` **and** `*.sarif`; the redaction runs at *write time* in the commands (not only at the gate); fp-check Phase-4 PoC files are scrubbed by the same filter before write. Redaction of the *report* is distinct from a tree-wide secret scan (that's Trivy/ADR-0001 on the repo).
- **V4 — masker mangles the evidence.** → PAN rule is **Luhn + IIN gated** (random/most non-card numbers survive — verified: order numbers, block heights without valid IIN, RNG seeds untouched); **standalone base58 deliberately NOT masked** so Solana addresses/signatures survive (verified in dogfood). **Residual:** a numeric claim that is *itself* a valid Luhn+IIN PAN is masked — reviewer reads source at the cited line.
- **V5 — placeholder-format suppression injection.** → Input NUL bytes are stripped before the sentinel pass; the SECRET value class excludes the `\x00` sentinel so masks can't be re-captured. The idempotency skip is best-effort; the bounded residual (an attacker-shaped literal `[REDACTED-…]` in target source suppressing an *adjacent* real secret) is accepted — the secret typically also appears verbatim elsewhere in the quoted source and trips a pattern there.
- **V6 — encoding/normalization bypass.** → Plaintext-only **by design**; base64/hex/percent/`\u`-escaped secrets are explicitly out of scope and owned by ADR-0004 (don't surface them) + human review. Documented, not silently assumed.
- **V7 — ReDoS / fail-open-by-frustration.** → All patterns are **linearly bounded** (`{12,18}`, `{6,256}`, lazy literal-anchored key blocks — no nested unbounded quantifiers). `--check` **fails closed** on any file over an 8 MB cap (anomalous for an audit report → manual review) rather than hanging or silently passing.
- **V8 — secret already committed before pre-push.** → Decision §1 scrubs at **write time**, so the secret is never written and never committed; the pre-push `--check` is a backstop. A pre-commit hook is recommended for tighter timing.

## Enforcement
- **`~/.claude/scripts/redact.py`** — `filter`/`--in-place` (write-time scrub, used by the commands) + `--check` (fail-closed gate). Pure stdlib. **Dogfooded E2E 2026-06-12** (ADR-0010): valid PAN masks / random 16-digit + Solana address + tx signature + RNG seed preserved / SSN・AWS・GitHub・JWT・URL-cred・PEM・`secret_key`・`private_key`・`xprv` mask / idempotent second pass / clean file exits 0 / oversized fails closed.
- **lint-gate stage** (`~/.claude/templates/lint/lint-gate.sh` + repo `.githooks/lint-gate.sh`): `redact.py --check` over `.audit/**/*.md` + `.audit/**/*.sarif`; self-skips when no `.audit/` exists; aborts the push on any hit.
- **/audit + /attack write steps** cite: "pipe the rendered report through `redact.py` before writing `.audit/*.md`."
- Ties **ADR-0004** (output-side enforcement of "no plaintext secrets on disk") and **ADR-0006** (the report embeds untrusted target bytes).

## Threat-model review (grill-with-threat-model, 2026-06-12)
Hostile pass found **8 reachable break paths**; all folded into Security Considerations above. The load-bearing insight: **this control's honest value is narrow — a defense-in-depth backstop against verbatim-quoting of *announced* secrets into the primary on-disk report, NOT a containment boundary for the operator's keys** (V1/V2 kill that claim). Containment stays with ADR-0004 + ADR-0007.
1. Operator's own key formats (base58 Solana, byte-array, mnemonic, `secret_key`/`private_key`) not in the pattern set → fail-open on the primary asset.
2. Redaction is at disk-write, but the secret already entered context + went over the wire at Read-time.
3. `--check` scoped to `.audit/*.md` leaves SARIF, PoC files, scratch dirs, `*_errors.jsonl` unguarded.
4. The masker overwrites the exact value a finding-about-a-secret needs, and eats valid-Luhn non-card numbers → corrupted/altered evidence.
5. Attacker-influenced report content can pre-seed the placeholder literal to suppress an adjacent real secret.
6. Base64/hex/percent/`\u`-encoded secrets defeat every literal regex.
7. ReDoS/large-input over a multi-MB report hangs the pre-push gate → operator `--no-verify`s it (fail-open by frustration).
8. Pre-*push* `--check` fires after `git commit` — the secret is already in the local object store/reflog.
