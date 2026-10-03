# ADR-0012 — LLM-Surface Invariants (Secure the Apps We Build)

**Status:** Accepted · **Date:** 2026-06-17
**Supersedes:** — · **Superseded-by:** —
*(Grilled via `grill-with-threat-model` 2026-06-17 → 12 break paths found; mitigations folded below. The load-bearing honesty: this is **structural dev-time enforcement** — it checks that a boundary/handler/redaction **exists in-function at the LLM call-site**, deterministically and cheaply. It does **not** verify the boundary works (BP-2/9), follow cross-module flows (BP-6), cover dynamic prompt construction (BP-11), detect injection payloads, or contain a secret already read (BP-5). Those belong to the `/attack` human pass, the INJECT flagship's runtime pipeline, and ADR-0004/0007.)*

## Context
ADR-0002/0003/0006 harden the Claude Code harness we *run* against untrusted MCP configs, tool
descriptions, and bytes. Nothing governs the LLM-touching apps we *build*. The portfolio has a recurring
surface — untrusted data → LLM summary → output — across Stiletto, Dockyard, ShadowHunter (scan/scrape →
summarizer), Credence, and the INJECT flagship. The scanned target / scraped page is attacker-controlled
and becomes the LLM's input (indirect prompt injection); its output is rendered or acted on (insecure
output handling); secrets can leak into prompt context (sensitive-info disclosure).

Per our own INJECT research (`Prompt Injection Defense Architecture Design.md` §8): a transformer has no
structural difference between instructions and data, so perfect static *payload* detection is impossible.
Therefore this ADR enforces *architecture*, not payload detection. Payload detection is the INJECT
flagship's runtime job (defense-in-depth, leaky-by-nature).

## Decision
Every code path in a fevra-dev app that sends data to an LLM MUST satisfy:
1. **Boundary (inv-1).** Untrusted data reaches an LLM prompt only delimited/provenance-tagged — never by
   raw f-string/concat into the prompt. Untrusted = request input, scan results, scraped/dark-web content,
   file/RAG content, tool output.
2. **Output-is-untrusted (inv-2).** An LLM response reaches no dangerous sink (`exec`/`eval`/`subprocess`/
   SQL/file-write/HTML-render/downstream-tool-arg) without passing an output-handler first.
3. **No-secrets-in-context (inv-3).** Secrets/credentials/keys never enter LLM context unredacted
   (extends ADR-0011; the inv-3 rule restates a minimal subset of `redact.py`'s key patterns).

A "recognized boundary/handler" is matched by convention — a function whose name contains
`boundary`/`delimit`/`sanitize`/`redact`/`handle` — or, for asserted-safe cases, the auditable
suppression `# nosemgrep: <rule-id>  # llmsec: bounded — <reason>` (every such annotation is reviewed in
the `/attack` LLM-surface pass, like `# nosem`/`# noqa`). Agency/tool-abuse is out of scope here →
deferred to ADR-0013.

## Considered Options
- **Architectural-invariant enforcement (chosen)** — boundary/output/secret invariants enforced by
  Semgrep taint + audited in `/attack`. Enforceable, deterministic, matches the ADR→rule culture.
- **Runtime detection bundled into a shared library** — rejected: ships a framework (premature),
  couples every app to the unfinished flagship, and leans on leaky detection as the primary control.
- **ADRs + manual checklist, no gate** — rejected: no mechanical enforcement; relies on discipline.

## Consequences
Apps touching an LLM get a mechanical structural gate + a human audit pass, with zero runtime dependency
on the flagship. Cost: heuristic taint produces some false positives → the auditable `# nosemgrep …
llmsec: bounded` hatch keeps them from blocking pushes, at the cost of a `/attack` review obligation.
Detection of actual injection payloads is explicitly NOT provided here (flagship's job).

## Security Considerations & Mitigations (grill-with-threat-model)
Mapped to the 12 break paths found (full list below). Three of them (BP-2, BP-6, BP-11) are **inherent
to static structural enforcement** and are explicitly **not closed by the gate** — they are owned by the
`/attack` human pass and the flagship. This ADR makes no claim to close them; stating them is the point.

- **BP-1 — inv-1 source-set incompleteness (unmodeled untrusted source).** → The taint sources are tuned
  against the *real* call-sites in the portfolio apps during rule authoring + the ShadowHunter dogfood
  (add `httpx`/`aiohttp`/queue/DB sources as found). **Residual (accepted):** any enumerated-source rule
  is fail-open on a source it doesn't list. The backstop is the `/attack` pass, which enumerates **every**
  LLM call-site by hand and traces its inputs — it does **not** rely on the rule's source list. Documented,
  not hidden.
- **BP-2 — name-convention sanitizer is semantically blind.** → **Accepted residual / by design.** The
  ADR's Decision says the harness *flags the violation, it does not ship the fix*; recognition is by
  convention precisely because we don't ship a verified library. The gate enforces *that a boundary/handler
  exists at the call-site*, not *that it neutralizes*. Whether `handle_*`/`sanitize_*` actually neutralizes
  is a mandatory `/attack` review item (same class as `# nosem`). This is the central scope limit, stated
  up front in the header note.
- **BP-3 — `llmsec: bounded` hatch abused.** → Every annotation MUST name the rule id + a reason and is a
  **finding-to-justify** in `/attack` (`grep -rn 'llmsec: bounded'`); per ADR-0009 the reviewer re-reads the
  **actual flow**, never the prose. **Residual (accepted):** between audits an abused hatch is silent —
  identical to `# noqa`/`# nosem` and accepted on the same terms.
- **BP-4 — inv-2 sink-set incompleteness.** → The sink set is expanded as new dangerous sinks are met
  (template render, ORM/raw SQL, `pickle`/`yaml.load`, HTML response, log→automation, downstream-tool-arg).
  **Residual (accepted):** a novel sink fails-open until added; the `/attack` pass maps output against **all**
  sinks by hand, not just the rule's list.
- **BP-5 — inv-3 secret-source incompleteness.** → inv-3 is explicitly a **backstop subset** of `redact.py`,
  not containment — it mirrors ADR-0011's V1/V2 honesty. Secrets from a config object / secrets-manager SDK
  / file are **residual** and owned by **ADR-0004** (don't read secret material into context) + the `/attack`
  pass. No false "secrets can't reach the prompt" assurance.
- **BP-6 — cross-function / cross-file taint loss (Semgrep intraprocedural limit).** → **Accepted residual /
  inherent.** The rule catches the *in-function* violation (the common quick-and-dirty shape). Structural
  assurance across modules is the `/attack` enumeration's job; payload assurance is the flagship's. The ADR
  does not claim interprocedural coverage.
- **BP-7 — opt-in seeding leaves the gate absent.** → Opt-in is the deliberate v1 posture (prove low-FP
  first). The `/attack` LLM-surface pass triggers on an LLM-SDK **import**, regardless of whether `.semgrep/`
  rules are seeded — so the human audit covers un-seeded repos. Auto-seed via `macdaddy` is the follow-on
  once FPs are measured. **Residual (accepted + documented):** a repo that neither seeds the rules nor gets
  `/attack`'d is uncovered.
- **BP-8 — Python-only rules.** → **Scoped, not hidden.** v1 is Python-first (where the LLM apps live —
  portfolio is Python-dominant). The `/attack` pass is language-agnostic (enumerates call-sites by hand), so
  it covers JS/TS/Kotlin/Dart LLM apps *before* language-specific rules exist. JS/TS rules are an explicit
  follow-on, not a silent gap.
- **BP-9 — sanitizer-name regex over-broad (incidental whitelisting).** → Same root as BP-2 (name ≠
  behavior). Tightening the anchor trades against FPs; the `/attack` pass reviews handler **semantics**, which
  is the real check. **Residual (accepted):** a pre-existing `handle_*`/`sanitize_*` can incidentally clear
  taint until the audit catches it.
- **BP-10 — response-shape taint propagation gap.** → The `semgrep --test` fixtures exercise the real
  SDK access patterns (`resp.content[0].text` for Anthropic; OpenAI `choices[0].message.content` added to
  fixture coverage) so propagation is **verified empirically**, not assumed. **Residual:** exotic access
  (streaming, `.model_dump()`) may drop taint → `/attack`.
- **BP-11 — dynamic construction: structure-present ≠ safe.** → **Accepted residual / inherent.** A static
  gate asserts syntax, not runtime dataflow. Dynamic assembly (`parts.append(...)`, `TEMPLATES[k].format`)
  is owned by the flagship (runtime) + the `/attack` pass. Stated alongside BP-2/BP-6 as a core limit.
- **BP-12 — in-repo rules are mutable by the actor they constrain.** → The **canonical** rules are the
  machine-layer template (`~/.claude/templates/semgrep/llm-surface/`, single source of truth); a weakened
  in-repo `.semgrep/` is detectable by diffing against the template (an `/attack` check) and by the same
  code-review that should catch the vuln PR. **Residual (accepted):** the gate trusts the repo's own rules —
  identical to every in-repo lint config (Oxlint/Biome) and consciously covered by **ADR-0007** (review
  repo-shipped agent/CI config before trusting it).

## Threat-model review (grill-with-threat-model, 2026-06-17)
Hostile pass found **12 reachable break paths**; all folded into Security Considerations above. The
load-bearing insight (cf. ADR-0011): **this control's honest value is narrow.** It deterministically
enforces that an LLM call-site has a *structural* boundary / output-handler / secret-redaction **present
in-function**, and forces every asserted-safe exception into an auditable annotation. It does **not** verify
those structures work (BP-2/9), does **not** follow cross-module flows (BP-6), does **not** cover dynamic
prompt construction (BP-11), does **not** detect injection payloads, and does **not** contain a secret
already read into context (BP-5). Those are owned by the `/attack` human pass (cross-module structure +
annotation justification + handler semantics), the **INJECT flagship** (runtime payload detection), and
**ADR-0004/0007** (secret containment + repo-config review).
1. inv-1 source-set incompleteness — untrusted data via an unmodeled source (`httpx`/DB/queue/argv) flows raw → prompt.
2. Name-convention sanitizer is semantically blind — `def sanitize(s): return s` clears taint (defeats inv-1/2/3).
3. `# nosemgrep … llmsec: bounded` hatch abused to silence a real finding between audits.
4. inv-2 sink-set incompleteness — output → template-render/ORM/`pickle`/HTML/log-automation is unguarded.
5. inv-3 secret-source incompleteness — secret from a config object / secrets-manager SDK / file evades the source set.
6. Cross-function / cross-file taint loss — layered source→sink across modules never fires.
7. Opt-in seeding — an un-seeded LLM repo self-skips the Semgrep stage and shows green.
8. Python-only rules — JS/TS/Kotlin/Dart LLM call-sites are invisible to the gate.
9. Sanitizer-name regex over-broad — a pre-existing `handle_*`/`sanitize_*` incidentally whitelists a real flow.
10. Response-shape taint gap — `resp.content[0].text` / `choices[0].message.content` chains drop the taint link.
11. Dynamic prompt construction — structure-present ≠ safe; static gate can't see runtime assembly.
12. In-repo `.semgrep/` rules are editable by the same PR that adds the vulnerable flow.

## Enforcement
- `~/.claude/templates/semgrep/llm-surface/*.yaml` — three taint rules (inv-1/2/3), seeded into an
  app's `.semgrep/`, run by the existing lint-gate Semgrep stage (`semgrep --config .semgrep`).
- `/attack` LLM-surface pass (`~/.claude/commands/attack.md`) — enumerates LLM call-sites, maps them to
  inv-1/2/3, and audits every `# nosemgrep … llmsec: bounded` annotation.
- Ties ADR-0011 (inv-3 secret handling) and ADR-0006 (untrusted bytes reaching a sink).

## Output Schema Impact
**Schema Change Type:** none
