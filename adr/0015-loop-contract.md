# ADR-0015 — Loop contract: bounded autonomous iteration

**Status:** Accepted (grilled 2026-06-22, 11 break paths folded; **C1 mechanism corrected at pre-build double-check** — the schedulers give an external *trigger*, not a native agent-immutable budget; the immutable cap is an external driver or operator-supervision) · **Date:** 2026-06-22
**Supersedes:** — · **Superseded-by:** —

## Context

The workflow has grown several ways to run *iterative / self-directed* work — `/loop` (fixed-interval
re-invocation), `ScheduleWakeup` (dynamic self-paced re-invocation), `/autopilot` (long multi-step
runs), and the long single-goal sessions that produced ADR-0013/0014 (the harness-eval dogfoods). It
also has the continuity/verification pieces those runs lean on, but *scattered*: `feature_list.json`
(progress across compaction), `goal`/goal-ledger (`.agent/runs/<goal-id>/`), `harness-eval` (the
verification gate), and the `.audit/` handoff artifacts. What is missing is a single **contract** that,
*before* an autonomous loop starts, fixes what it is allowed to do and when it must stop. Today each
long run improvises this — exit criteria live in the operator's head, the "verify each iteration" step
is ad-hoc, and there is no declared budget/stall stop.

This is a real failure surface, not a nicety. An unbounded self-directed loop is exactly where the
known autonomy failure modes bite (HarnessX arXiv 2606.14249 names them: reward-hacking,
catastrophic-forgetting, under-exploration), and it is a **security** surface: a loop running unattended
over a repo can have its goal redirected by injected content in repo files or tool output
(ADR-0002/0003/0006), and without a per-iteration verification gate a hijacked or drifted iteration
runs N more times before anyone looks. The arch-satisfaction loop (forwardfuture.ai loop library) and
Zhou's loop-contract pattern converge on the same shape: *success criteria up front → one bounded change
→ verify → checkpoint → state file → explicit exit*. This ADR adopts that shape as a **consolidation**
of the five existing artifacts — one declared contract that points at them — not a new loop framework.

## Decision

Any run that performs **more than one self-directed iteration without returning to the operator**
(every `/loop`, `ScheduleWakeup`-driven, and `/autopilot` session, and any agent-initiated "repeat until
done" loop) MUST be governed by a declared **loop contract** before the first iteration. The contract
is a single JSON artifact — a `loop_contract` block inside `feature_list.json`, or a standalone
`loop-contract.json` when no feature list exists (JSON not markdown, for the same reason
`feature_list.json` is: agents overwrite it less) — carrying the five required fields below plus a
`criteria_sha` fixed at start.

**The grill (2026-06-22) established the governing reality: a contract authored, executed, measured, and
reported by the same agent it constrains is a *cooperative* protocol — it binds an agent that wants to be
bound and is transparent to one that has drifted, been injected, or is reward-hacking.** So the
load-bearing enforcement MUST sit *outside* the agent; the in-context contract is the disciplined-scoping
layer on top. Three controls are external/immutable by construction:

- **(C1) The binding budget is external to the agent — a driver, not the agent's own re-arming.**
  Corrected against the actual primitives (pre-build double-check, 2026-06-22): `/loop <interval>` is a
  harness-driven *trigger* (the agent does not control firing) but has **no native iteration cap** — it
  runs until the operator stops it; `ScheduleWakeup` / dynamic `/loop` is **agent-re-armed each turn**
  ("omit the call to end the loop") so its continuation is agent-*mutable*, NOT a budget; `Cron` runs
  until deleted and the agent holds `CronDelete`. An *agent-immutable* cap therefore exists only via
  **(a) an external driver** — a fixed-count shell loop (`for i in $(seq N); do claude -p …; done`) or a
  Cron routine whose count an external counter enforces — or **(b) an operator-supervised** interval
  `/loop` (the operator is the immutable stop). A loop touching untrusted input or irreversible actions
  MUST use (a); a bare agent-re-armed `ScheduleWakeup`/dynamic `/loop` has only a discipline-level budget
  and is not authorized for unattended untrusted/irreversible work. This is the control that actually
  fails the loop closed, and it lives outside the agent by construction.
- **(C2) `exit_criteria` and `stop_conditions` are frozen at start (`criteria_sha`).** They are hashed
  when the loop launches and are immutable for its lifetime; any divergence is a **stop-and-surface**
  event, never an in-loop relaxation. This closes reward-hack-by-edit and budget-reset (a loop that
  wants different criteria stops and asks). It **generalizes the guard already shipped in
  `feature_list.json`** ("it is unacceptable to weaken, delete, or rewrite a feature's 'verify' to make
  it pass… a feature flips to 'pass' only when its ORIGINAL verify succeeds") from one feature's verify
  to the loop's exit_criteria.
- **(C3) `state_file` is untrusted-on-resume.** It carries only agent-derived conclusions + metadata —
  never verbatim untrusted repo/tool bytes (the ADR-0013 "echo only metadata" precedent) — its path is
  scoped to the loop's working dir (no traversal), and on resume it passes ADR-0006 sanitization before
  being trusted. This closes the trust-laundering and path-clobber paths.

A loop with no valid contract **and no operator-set outer budget** runs at most one iteration and then
stops for the operator. **Honesty bound (no over-claim, ADR-0013):** per-iteration *mechanical* gating
holds only when the loop is driven through an external wrapper that calls the validator between steps
(`loop-contract --check && <iteration>`); the bare `/loop`/`ScheduleWakeup` primitives re-invoke
unconditionally, so for them the enforcement is **C1 (the immutable outer budget) + discipline**, not a
per-iteration mechanical stop. The ADR does not claim otherwise — claiming mechanical fail-closed for
bare re-invocation would be the false-assurance ADR-0013 forbids.

Required contract fields (all five mandatory; missing any → not a valid contract):

1. **`exit_criteria`** — the testable definition of "done"/"satisfactory," fixed before the loop runs
   (e.g. "all `bad` corpus cases red AND all `clean` green," "`/harness-gc` step 0 passes," a coverage
   threshold). Prose-only ("until it looks good") is not valid.
2. **`bounded_action`** — the single reviewable change one iteration may make (one commit-sized step).
3. **`verification_gate`** — the check each iteration MUST pass before its checkpoint. For
   logic-critical loops the gate is **verifier ≠ discoverer** (ADR-0009): the iteration's own output is
   a pointer, re-verified against the artifact, never trusted as evidence.
4. **`state_file`** — the path that carries decisions/blockers/next-action across iterations and
   compaction (`feature_list.json`, a goal-ledger run dir, or `.audit/*`). Updated every iteration.
5. **`stop_conditions`** — explicit budget (max iterations / wall-clock / cost) **and** stall detection
   (no progress against `exit_criteria` for K iterations → stop). Reaching either stops the loop.

Two guards ride on top, restating existing controls so an autonomous loop cannot route around them:

- **Destructive / outward-facing actions stay gated even in autonomous mode.** A loop never
  auto-approves a step that the same action would require confirmation for interactively (push, deploy,
  delete/overwrite not authored by the loop, sending content to an external service). Autonomy is over
  *what to work on next*, not over the irreversible-action confirmation boundary.
- **Untrusted-content redirection is the verification gate's job.** When a loop ingests repo files or
  tool output (ADR-0002/0003/0006), the `verification_gate` re-derives the next action from the
  artifact, not from any instruction found in ingested content — so an injected "now do X" cannot
  steer subsequent iterations past one bounded, verified step.

## Invariants

1. No autonomous loop touching untrusted input or irreversible actions runs without an **external (driver-enforced or operator-supervised) budget** (C1); a bare agent-re-armed `ScheduleWakeup`/dynamic `/loop` has only a discipline budget and is not authorized for unattended untrusted/irreversible work.
2. No autonomous loop runs a 2nd self-directed iteration without a valid 5-field contract; absent/invalid → stop after one iteration.
3. `exit_criteria` and `stop_conditions` are **frozen at start** (`criteria_sha`); any in-loop divergence is a stop-and-surface event, never a relaxation (C2).
4. Every iteration updates `state_file` and passes `verification_gate` before its checkpoint; a failed gate stops the loop, it does not "try again differently" unbounded.
5. `state_file` carries only agent-derived conclusions + metadata, scoped to the working dir, re-sanitized on resume (C3, ADR-0006) — never verbatim untrusted bytes.
6. `stop_conditions` (stall) are advisory progress hints; the **binding** stop is C1's outer budget (stall self-measurement is gameable — #5).
7. The irreversible-action confirmation boundary is never relaxed by being inside a loop (restates the harness's outward-facing-action rule).
8. A logic-critical loop's `verification_gate` is verifier ≠ discoverer (ADR-0009 inv-3 inside the loop body), and the verifier is a *fresh* principal, not the iterating context.

## Considered Options

- **Option A — one declared contract consolidating the five existing artifacts (chosen)** — adds a
  single front-door discipline + a small validator; reuses `feature_list.json`/goal-ledger/harness-eval
  rather than introducing a framework. Matches the consolidation bias and ADR-0008.
- **Option B — port the forwardfuture loop *library* (44 loop definitions)** — rejected: 80% off-domain
  (SaaS growth), accumulation not consolidation; the value is the *contract shape*, not the catalog.
- **Option C — leave it implicit (status quo)** — rejected: the failure modes above are unbounded and
  the security guard (injected-goal redirection over N unattended iterations) is real; "in the
  operator's head" is not a control.
- **Option D — full HarnessX self-editing foundry (auto plan→edit→critique→merge)** — rejected, same as
  ADR-0013 Option C: maximal autonomy surface for the tier that benefits least (operator runs Opus 4.8);
  ADR-0008 deliberately keeps the edit/cut decision human. This ADR bounds loops; it does not automate
  harness self-edit.

## Consequences

Easier: long runs become resumable and auditable (the contract is the handoff); a drifted/hijacked
iteration is caught at its gate instead of N iterations later; cost/stall are bounded by construction.
Harder: starting an autonomous loop now costs writing five fields first (the intended friction — it is
the `/scope` discipline applied to autonomy). Interactive single-shot work is unaffected (the trigger is
"≥2 self-directed iterations").

## Security Considerations & Mitigations

Grilled with `grill-with-threat-model` (2026-06-22, **11 break paths**). The cross-cutting finding: every
contract field is agent-mutable and agent-measured, and the two intended enforcement points
(`/loop`/`ScheduleWakeup` re-invocation; the validator) sit outside any mechanism that *forces* the agent
to consult them. That finding is what drove controls C1–C3 in the Decision. Dispositions below in the
ADR-0013 Closed-vs-Residual style.

**Closed by design (mechanical, do not rely on agent honesty):**
- **Reward-hack-by-edit / budget-reset / criteria-TOCTOU (#1, #6, #10-criteria):** C2 freezes
  `exit_criteria`+`stop_conditions` under `criteria_sha` at start; an in-loop edit diverges the hash →
  stop-and-surface. A loop that wants different criteria halts and asks; it cannot relax to "done."
- **The binding budget is external (#6-budget, #3-partial):** C1 puts the hard cap in the scheduler
  (`/loop` count / `ScheduleWakeup`+Cron deadline / wrapper-deletes-after-N), agent-immutable. Blast
  radius is bounded regardless of progress-honesty. This is the load-bearing fail-closed.
- **State_file trust-laundering + path-clobber (#4, #9):** C3 — `state_file` holds only agent-derived
  conclusions+metadata (never verbatim untrusted bytes), is working-dir-scoped (no traversal), and is
  re-sanitized on resume (ADR-0006). An injected "now do X" in repo/tool content cannot ride the
  continuity file into the next iteration's trust context.

**Documented residuals (cooperative / inference — bounded by C1, honest per ADR-0013):**
- **Fail-OPEN fallback on bare primitives (#3):** when a `/loop`-interval timer or a `ScheduleWakeup`
  the agent already armed fires, **no component sits between the wake-up and the agent's next action** —
  the fire itself is unconditional even though `ScheduleWakeup`'s *arming* is agent-driven (per C1). So
  per-iteration validator gating is real **only** under an external wrapper (`loop-contract --check &&
  step`). For bare primitives the enforcement is C1 (external/operator-supervised budget) + discipline —
  explicitly *not* claimed as a per-iteration mechanical stop. Highest residual; the mitigation is to
  prefer the wrapper for any loop touching untrusted input or irreversible actions, and C1's external
  budget caps the damage of a non-compliant agent.
- **Tautological criteria / stall-gaming / mega-iteration / forgetting-ratchet (#2, #5, #8, #11):** a
  shape-validator cannot distinguish `exit_criteria: {cmd: "true"}`, a no-op "progress" bump, a 50-step
  "single iteration," or a per-compaction criteria summary-drift from honest work. Bounded by (a) C1's
  cost cap, (b) the fresh-principal verifier (inv-8), (c) operator diff-review/approval of `exit_criteria`
  at sign-off (the launch is the human checkpoint). Same inference-honesty class as ADR-0013's
  `expect_exit` reward-hack residual — named, not pretended-closed.
- **Confirmation-bypass by decomposition / unrecognized sink (#7):** restates the harness's existing
  outward-facing-recognition coverage; splitting a `git push` across iterations or exfil via raw `curl`
  is the harness's recognition gap, not new to this ADR. Bounded by inv-7 + C1 + the standing
  "irreversible action needs authorization" rule.
- **Validator green-while-dead (#10):** inherited ADR-0013 environment-integrity residual — a stubbed
  validator or poisoned PATH disables the regime silently. The validator gains a harness-eval corpus case
  so its *death* is caught at `/harness-gc`; PATH-trust at loop time is the same assumption the whole
  lint-gate makes. Now load-bearing for autonomy, so flagged louder here.

**Net:** the ADR's real security value is C1 (immutable outer budget bounding blast radius) + C2/C3
(closing the self-relaxation and trust-laundering paths). It does **not** claim to make a hostile/drifted
agent honor the contract; it bounds what a non-compliant loop can do and makes the compliant path the
disciplined one. This is why it is **exempt** (autonomous-execution safety / fail-closed / untrusted-input
on resume) — the grill confirmed the security relevance is certain, not uncertain.

## Enforcement

- A stdlib `loop-contract.py` validator (`#!/usr/bin/env python3`, no third-party deps — the
  `ccost.py`/`docs-index.py` plain-stdlib house style; `harness_eval.py`'s PEP-723/`uv` is unnecessary
  here). Given a contract JSON (`--check <file>`) it exits non-zero if any of the five fields is
  missing/empty, `exit_criteria`/`stop_conditions` is prose-only or lacks a budget, `state_file` escapes
  the working dir (`..`/abs-escape — the C3 path-clobber guard), or `criteria_sha` diverges from the
  current `exit_criteria`+`stop_conditions` (the C2 freeze). Fail-closed (ADR-0005/0013): a
  malformed/absent contract = non-zero, never skip-to-green.
- The validator is registered as a comp-FB component in HARNESS.md and gains a `harness-eval` corpus
  case (good = 5-field contract → exit 0; bad = missing `stop_conditions` → exit 1), closing the
  inv-5 loop (ADR-0013).
- `/scope` (and the `/loop`/`/autopilot` front-doors) prompt for the contract before launching an
  autonomous run; CLAUDE.md harness-governance gains a one-line pointer.
- Process discipline (like ADR-0008/0009/0010): the validator checks *shape*; the operator/diff-review
  checks that `exit_criteria` is honest (a weakening edit is a visible, security-relevant diff).

## Output Schema Impact
**Schema Change Type:** additive
**Schema File:** `feature_list.json` gains an optional `loop_contract` object (5 fields). New consumers:
the validator only. No existing consumer reads it → **Consumer Audit Required:** no.

## Semantic Drift Assessment
- **Type/Format stability:** new optional block, not a changed field.
- **Verification plan:** the harness-eval corpus case (valid→0 / missing-field→1) is the executed proof,
  plus an E2E dogfood (ADR-0010): start a loop with no contract → confirm it halts after one iteration.
