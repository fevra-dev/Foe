# ADR-0000 — Instructions for Agents

**Status:** Accepted · **Date:** 2026-06-06

This directory holds Architectural Decision Records (ADRs): immutable, numbered decisions that constrain how this codebase is built. Treat ADRs as hard rules, not suggestions.

## Rules for any coding agent in this repo
1. **Read `/adr` before writing or modifying code.** Load every `NNNN-*.md`. These decisions override defaults and convenience.
2. **A new architectural decision = a new ADR.** For any structural/security/dependency decision not covered by an existing ADR, draft `NNNN-<slug>.md` from `TEMPLATE.md`, get sign-off, then implement.
3. **Every enforceable ADR ships an enforcement rule.** If a constraint is statically checkable, author the Semgrep/CodeQL rule (via `semgrep-rule-creator`) or lint rule so CI fails on violation.
4. **ADRs are append-only.** Never edit an Accepted ADR's decision. To change course, write a new ADR and set `Supersedes:`/`Superseded-by:`.
5. **Adversarial review before lock-in.** Run an adversarial threat-model review (the `grill-with-threat-model` skill, once installed) on any security-relevant ADR before Status → Accepted.

## Lifecycle
Proposed → (adversarial threat-model review) → Accepted → (optionally) Superseded.

## Index
- 0001 — Zero-trust dependencies
- 0002 — No auto-loading of untrusted MCP / agent configs (CVE-2026-40933 class)
- 0003 — Review MCP tool descriptions for hidden instructions (Ruflo #1375 pattern)
- 0004 — Secret handling & zeroization
- 0005 — Fail closed on security decisions
- 0006 — Sanitize untrusted bytes before any sink
- 0007 — Review repo-shipped agent config & hooks before opening a session (CVE-2025-59536 class)
- 0008 — Harness components are build-to-delete (reviewed on model upgrade)
- 0009 — Fan out for discovery, single-thread for logic-critical work
- 0010 — Self-built tools are dogfooded end-to-end before "done"
- 0011 — Redact agent-generated reports at the write boundary
- 0012 — LLM-surface invariants (secure the apps we build)
