# ADR-0001 — Zero-Trust Dependencies

**Status:** Accepted · **Date:** 2026-06-06
**Supersedes:** — · **Superseded-by:** —
**Amended-by:** Addendum 2026-08-24 (below) — **§Enforcement's Semgrep bullet is corrected there.**

## Context
This stack builds security/offensive tooling where supply-chain risk and minimal attack surface are first-order concerns. Agents reach for `npm install`/`pip install` reflexively, importing transitive risk and bloat.

## Decision
No third-party dependency may be added without a written justification that: (a) names the exact package + version + source; (b) compares its supply-chain risk (maintainers, last release, known CVEs via Trivy/cve-mcp, transitive count) against the cost of an in-house implementation; and (c) records the decision here or in the PR. Prefer stdlib / in-house for trivial functionality. Pin exact versions; no floating ranges.

## Consequences
Slower to add deps; leaner, more auditable, more performant, stealthier tools. Forces a builder mindset.

## Security Considerations & Mitigations
- Typosquat / dependency-confusion → every new dep scanned with `trivy fs` + cross-checked against `cve-mcp` before adoption.
- Transitive bloat → justification must include the transitive dependency count.
- Compromised maintainer → prefer lockfile integrity / reproducible builds; pin + verify hashes.

## Enforcement
- `trivy fs --scanners vuln,secret,license <repo>` in the pre-push gate (Thread 3).
- Pre-push hook: fail if a lockfile changed without an accompanying justification note.
- Semgrep rule flagging un-pinned version ranges in dependency manifests. *[never built — see Addendum 2026-08-24]*

## Enforcement status — Addendum 2026-08-24 (append-only)

Written by the thread-17 enforcement audit, which asked of every ADR: *does the named enforcer
exist, where does it run, and has anyone deleted it and watched?* Two of this ADR's three
Enforcement bullets needed correcting, in opposite directions.

**Bullet 3 (Semgrep un-pinned version ranges) — NEVER BUILT.** No such rule exists.

**Bullet 2 (pre-push lockfile-justification hook) — BUILT, under another ADR's name.** This was
listed here as enforcement from 2026-06-06 and was not built for ten weeks. It exists now as
**ADR-0017 checker B**, `.githooks/security-review-trailer.py` (landed 2026-08-16, `bb8b510`), which
runs as gate stage 0b and fails a push whose lockfile / `.trivyignore.yaml` / patch-set change
carries no `SECURITY-REVIEW:` trailer. ADR-0017 §6 states the relationship — *"this implements the
lockfile-justification control ADR-0001 named but never built"* — but only in that direction. A
reader of ADR-0001 alone had no route to it. This paragraph is that route.

**Bullet 1 (`trivy fs`) — real and running:** `required: trivy` in `.githooks/gate-baseline`, so
removing it is a reviewed diff rather than a silent absence.

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
