# ADR-0008 — Harness components are build-to-delete, reviewed on model upgrade

**Status:** Accepted · **Date:** 2026-06-12 · **Last-reviewed:** 2026-06-12

## Context

Agent = Model + Harness. Every harness component (ADR, gate stage, hook, skill, command, routing
rule, sandbox) encodes an assumption about what the model *can't* do on its own. As models improve,
some assumptions expire and the component becomes pure overhead — tokens spent every run for zero
quality. Anthropic's own published example: Opus 4.5 needed sprint-decomposition; 4.6 made it dead
weight; 4.7 began self-verifying, shrinking the evaluator. This stack is on **Opus 4.8**, is dense,
and grew three sections in one week. The operator's standing principle is "bias toward consolidation."
Without a deliberate prune, harness decay accumulates silently.

The adversarial review of this very ADR (`grill-with-threat-model`, 2026-06-12) showed the naive form
of the prune is *more dangerous than the decay it fixes*: an allowlist exemption fails open, "measure
routine quality and delete if unchanged" is structurally blind to security controls (a defense only
changes output on adversarial inputs you don't see in normal runs), and the same model self-grading
its own need for a guardrail is the doer-equals-judge anti-pattern. So the decision below is built
**default-keep**: the burden of proof is on removal, not retention.

## Decision

Maintain `HARNESS.md` as the registry of every load-bearing harness component, each tagged with the
model-incapability it assumes. On every model upgrade (primary trigger) — or quarterly as a fallback —
run the `/harness-gc` prune ritual. **The default disposition of every component is keep.** A component
becomes cuttable only when *both* hold: (a) it is not exempt (invariant 3), and (b) its assumed
incapability is **disproven by an actual adversarial test** — the threat or work it handles is
attempted and the current model demonstrably handles it unaided (invariant 5). Routine-run "quality
looked the same" is **not** a cut criterion. Every decision is recorded in the `HARNESS.md` prune log
before any removal.

## Invariants

1. Every custom harness component has a `HARNESS.md` registry row naming its assumed incapability and a `Last-reviewed` date. *(Statically checkable: registry completeness.)*
2. `/harness-gc` runs on each model upgrade or at least quarterly; `HARNESS.md` "Last full review" reflects it, and records the exact model id reviewed against.
3. **Exemption is by property, default-deny (fail-closed).** A component is exempt from pruning if it is a security, fail-closed, or untrusted-input defense — *or if its security relevance is uncertain* (uncertain ⇒ exempt). Exemption is NOT a named list: any present or future component with that property is covered automatically. An exempt component may NEVER be cut for being "quiet" **or** for being "redundant / the model does this now" — the only path to retiring one is invariant 5 plus explicit operator sign-off.
4. No component is removed without a prior dated prune-log entry. Downgrading an exempt component's status (e.g. `exempt`→`candidate`) is itself a security-relevant change and must be justified in the prune log; because `HARNESS.md` is git-tracked, such downgrades are visible in diff/review.
5. **Adversarial-test gate.** A component is cuttable only when the threat/work it defends against is actually attempted and the current model handles it — a *recorded* adversarial test (per the ADR-0010 dogfood discipline), not a self-assessment and not routine-run quality. For exempt components the test bar is mandatory and the operator confirms the test design.
6. **Default-keep + dependents check.** The ritual's default is keep; the burden of proof is on the cut. Before any cut is proposed, its registry dependents are identified (what quietly consumes this component) so an isolated "turn off and measure" cannot mask a downstream break.

## Enforcement

- `eslint axia/` — n/a (no JS).
- **Registry-completeness check** (invariant 1) — every custom skill/command/ADR/gate-stage/hook has a row. Run manually during `/harness-gc`; promote to a script only if the manual check proves insufficient (build-to-delete applies to the enforcer too — do not pre-build it).
- **git diff review** (invariant 4) — `HARNESS.md` status downgrades surface in commit review; an `exempt`→other downgrade without a prune-log justification is a defect.
- `/harness-gc` (`~/.claude/commands/harness-gc.md`) — the semantic arm: enforces default-keep, the property exemption, the adversarial-test gate, and the dependents check. No static rule can decide "the model now does this natively," which is exactly why the gate is an *executed test*, not a judgment.

## Security Considerations & Mitigations

Grill findings (2026-06-12) and disposition:

- **Allowlist fails open / redundancy-rationale cut (F1, F2):** fixed — invariant 3 is property-based + default-deny; exempt components can't be cut for "quiet" *or* "redundant."
- **Doer grades own homework (F3):** mitigated — the cut criterion is an *executed* adversarial test (invariant 5), not the model's self-report. Residual: the model still designs the test; the operator confirms test design for exempt components. (A cross-model second-opinion gate was considered and left optional — invokable via `second-opinion` when desired.)
- **"Measure quality, delete if unchanged" is blind to security controls (F4):** fixed — routine quality is explicitly *not* a cut criterion (invariant 5).
- **Registry one-char tamper / no integrity gate (F5):** mitigated by git-tracking + invariant 4 (downgrade must be justified, visible in diff). Residual accepted: no SHA gate (build-to-delete applies to the enforcer).
- **Cascading-dependency blindness (F6):** mitigated — invariant 6 dependents check before any cut.
- **Silent model auto-update defeats the trigger (F7):** mitigated — invariant 2 records the model id reviewed against; `/harness-gc` flags when the running model ≠ last-reviewed model. Residual accepted: no automatic on-upgrade hook.
- **ADR primes the judge to delete (F8):** fixed — default-keep inverts the burden; the consolidation principle motivates *running the review*, not *cutting* in it.

## Consequences

Costs the operator a periodic review, the discipline to register new components, and an actual
adversarial test before any cut. Buys a dense stack that sheds genuinely-decayed scaffolding as models
improve — without the prune itself becoming the attack surface that strips defenses. The honest
tradeoff: default-keep means some real overhead survives longer than strictly necessary (you pay tokens
for a component until a test proves it dead), which is the deliberate price of never accidentally
deleting a guardrail.

## References

- Registry: `HARNESS.md`
- Ritual: `~/.claude/commands/harness-gc.md`
- Test discipline: ADR-0010 (dogfood E2E before done)
- Source: `docs/ideas/Harness engineering.md` (harness decay / build-to-delete), `docs/ideas/Booworkflow gold.md`
- Grill: `grill-with-threat-model` pass, 2026-06-12 (8 findings, dispositioned above)
