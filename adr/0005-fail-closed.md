# ADR-0005 — Fail Closed on Security Decisions

**Status:** Accepted · **Date:** 2026-06-07
**Supersedes:** — · **Superseded-by:** —
**Amended-by:** Addendum 2026-08-24 (below) — **§Enforcement's Semgrep bullet is corrected there.**
*(Threat-model review: `grill-with-threat-model` pass below.)*

## Context
A security control that fails *open* — granting access on error, timeout, or ambiguity — is worse than no control, because it gives false assurance. AI-generated code frequently defaults to permissive error handling.

## Decision
Every security-relevant decision (authentication, authorization, validation, signature/permission check, policy evaluation) MUST default to **deny** on any error, exception, timeout, missing input, or unrecognized value. No path may turn an *absent* or *failed* check into an *allow*.

## Consequences
Occasional false denials under fault conditions — acceptable (fail safe). Eliminates fail-open as a vulnerability class.

## Security Considerations & Mitigations (grill-with-threat-model)
- **Exception → default allow** — a `catch`/`except` around the check returns `true`/allows. → the only safe value from a failed security check is deny.
- **Unknown input treated as pass** — an absent field or unrecognized enum hits a permissive default branch. → unknown/missing = deny; make deny the default arm.
- **Timeout → proceed** — the auth/policy backend times out and the caller continues. → timeout = deny, never "assume allowed."
- **Short-circuit skips the deny** — early-return or `&&`/`||` short-circuit leaves the deny branch unreached on some path. → structure so deny is the fall-through default, not an `else` a path can bypass.

## Enforcement
- `differential-review` on every PR (its core job is flagging fail-open patterns).
- Semgrep rule: flag error/`catch` branches in auth/validation modules that return allow/true. *[never built — see Addendum 2026-08-24]*
- `insecure-defaults` review of new security-relevant components.

## Enforcement status — Addendum 2026-08-24 (append-only)

Written by the thread-17 enforcement audit. This ADR's *real* enforcement is not in its Enforcement
section at all — it is in `HARNESS.md` §1b, which maps **ADR-0005 → the fail-closed exit map of every
validator** (`harness_eval`, `loop-contract.py`, `validate-findings.py`, `scan-*`: an absent tool or
unparseable input errors, never skips to green). That is a genuine deterministic boundary, and it is
what makes this ADR a control rather than a comment. The three bullets below are weaker than the map.

| Bullet | Status 2026-08-24 |
|---|---|
| `differential-review` **on every PR** | The skill exists; *"on every PR"* is enforced by nothing. No CI job, no gate stage, no required check invokes it. Treat as a practice, not a control |
| Semgrep fail-open rule | **Never built.** See shared evidence below |
| `insecure-defaults` review | Skill exists; invoked on demand |

**Do not read the two "never built"/"unenforced" rows as a gap to close with new controls.** ADR-0019
made the gate's own decline paths structural (`skip_na` vs `skip_tool`, `STRICT` from CI), which is
this ADR's principle enforced where it can actually be enforced. The bullets are stale drafting from
2026-06-07, corrected here rather than built.

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
