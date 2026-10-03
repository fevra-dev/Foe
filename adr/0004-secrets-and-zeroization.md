# ADR-0004 — Secret Handling & Zeroization

**Status:** Accepted · **Date:** 2026-06-07
**Supersedes:** — · **Superseded-by:** —
**Amended-by:** Addendum 2026-08-24 (below) — **§Enforcement's Semgrep bullet is corrected there.**
*(Threat-model review: `grill-with-threat-model` pass below.)*

## Context
This stack builds security/offensive tooling that handles credentials, keys, tokens, and other secrets. Leaked or lingering secrets — in memory, on disk, in logs, or in crash artifacts — are a primary failure mode.

## Decision
1. No secret is hardcoded in source, config, or test fixtures (use env / a secret store).
2. Secrets are never written to logs, stdout/stderr, error messages, or persisted to disk in plaintext.
3. Sensitive buffers (keys, plaintext, derived material) are zeroized immediately after use with a **guaranteed-wipe** primitive — never a plain `memset` the optimizer can elide.

## Consequences
More care around secret lifetimes and buffer handling; eliminates the most common credential-leak classes from the toolchain.

## Security Considerations & Mitigations (grill-with-threat-model)
- **Dead-store-eliminated wipe** — the compiler removes a `memset(buf,0,n)` "useless" store, leaving the secret in memory. → use `explicit_bzero` / the `zeroize` crate / `SecureZeroMemory` / volatile writes; verify with the `zeroize-audit` skill.
- **Secret survives in copies** — string concat, format, realloc/GC move, or interpolation copied the secret before the wipe; the original is wiped but copies persist in heap/swap. → minimize copies, prefer fixed buffers, wipe derived buffers, `mlock` sensitive pages.
- **Leak via error/exception path** — secret lands in a stack trace, panic message, or debug log. → redact in error paths; secret-typed wrappers whose Debug/Display is masked.
- **Persisted via core dump / swap** — a crash dump or swap page writes the secret to disk. → disable core dumps for the process; lock sensitive pages out of swap.

## Enforcement
- `trivy fs --scanners secret` + a gitleaks-style pre-commit (no hardcoded secrets).
- Semgrep rule: flag logging / printing / disk-writing of secret-typed values. *[never built — see Addendum 2026-08-24]*
- `zeroize-audit` on wipe paths; `constant-time-analysis` where comparison timing matters.

## Enforcement status — Addendum 2026-08-24 (append-only)

Written by the thread-17 enforcement audit. Bullet by bullet:

| Bullet | Status 2026-08-24 |
|---|---|
| `trivy fs --scanners secret` | **Real.** `required: trivy` in `.githooks/gate-baseline`; runs pre-push and in CI |
| *"+ a gitleaks-style pre-commit"* | **Never built.** `command -v gitleaks` → not installed, and this repo has no pre-commit hook at all (`ls .git/hooks/` shows only `.sample` files). The pre-**push** trivy secret scan is what actually covers this intent — which is a weaker position than the bullet implies, because a secret is already in the local object store and reflog by the time a pre-push hook runs (the same gap ADR-0011's grill records as break path 8) |
| Semgrep secret-logging rule | **Never built.** See shared evidence below |
| `zeroize-audit` / `constant-time-analysis` | **Real** — both installed as plugin skills, invoked on demand. Not automatic, and not claimed to be |

**Shared evidence (2026-08-24).** The only operator-authored Semgrep ruleset on this machine is
`llm-surface` (ADR-0012), three rules:

```
$ find ~/.claude/templates/semgrep -type f -name '*.yaml'
/Users/fevra/.claude/templates/semgrep/llm-surface/llm01-untrusted-into-prompt.yaml
/Users/fevra/.claude/templates/semgrep/llm-surface/llm05-output-into-sink.yaml
/Users/fevra/.claude/templates/semgrep/llm-surface/llm06-secret-into-prompt.yaml
```

This repo's `gate-baseline` records the consequence honestly — `exempt: semgrep  no .semgrep/ rules
authored in this repo` — so the gate is not claiming to run a rule that does not exist.

**The F2 audit knew this on 2026-06-25** and wrote it into **ADR-0006 only**. ADR-0001, ADR-0004 and
ADR-0005 each name a Semgrep rule from the same never-built set and were left reading as current for
two months. A correction applied to one sibling of four is the propagation failure this addendum
closes — the same shape as silent-pass Q9, one level up: not a stale claim inside a document, but a
correction that stopped at the first document it applied to.
