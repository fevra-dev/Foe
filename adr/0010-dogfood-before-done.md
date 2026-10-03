# ADR-0010 — Self-built tools are dogfooded end-to-end before "done"

**Status:** Accepted · **Date:** 2026-06-12 · **Last-reviewed:** 2026-06-12

## Context

Code can be logically flawless and still fail when used. Architectural and integration bugs only
surface when the software is *used as a user would use it* — "dogfooding." The Harness Engineering
article's Evaluator pattern (browser automation testing the running app) and Bootoshi's "dogfood it,
run e2e" land on the same rule. This stack already does this in spots — the schema-contract checker
was tested against synthetic drift inputs; `scan-repo-agent-config.sh` against a synthetic malicious
repo — but it is not a stated invariant, so it can be skipped under time pressure.

## Decision

Any tool/skill/command/script built in this workflow is exercised end-to-end against a real or
synthetic target — as a user, not just via unit tests — before its status becomes "done," and the
dogfood run is recorded (what was run, against what, the observed result).

## Invariants

1. A recorded E2E/dogfood run exists before any self-built tool is declared done.
2. For security tools, the dogfood run includes a **synthetic adversarial target** (the `scan-repo-agent-config.sh` vs synthetic-malicious-repo precedent) — the tool must catch what it claims to catch.

## Enforcement

- `gavel` / semantic, layered on superpowers `verification-before-completion` (this ADR adds the
  "as a user, E2E" specificity for tools; it does not duplicate that skill).

## Consequences

Costs a real exercise run per tool. Buys reliability: the architectural bugs that look bug-free on
read get caught before the tool is trusted. Cheap insurance for a stack whose tools gate security work.

## References

- superpowers `verification-before-completion`
- Builds toward: ADR-0008 §5 (the build-to-delete adversarial-test gate reuses invariant 2's "attempt the threat, observe the result" discipline as its cut criterion).
- Precedents: `~/.claude/templates/schema-contract/` tests, `~/.claude/scripts/scan-repo-agent-config.sh`
- Source: `docs/ideas/Booworkflow gold.md` (dogfooding), `docs/ideas/Harness engineering.md` (Evaluator/E2E)
