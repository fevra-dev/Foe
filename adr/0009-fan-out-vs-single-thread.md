# ADR-0009 — Fan out for discovery, single-thread for logic-critical work

**Status:** Accepted · **Date:** 2026-06-12 · **Last-reviewed:** 2026-06-12
**Reinforced-by:** Addendum 2026-09-06 (below) — **invariant 3 gains a mechanical reason; nothing above is corrected.**

## Context

With frontier models (Opus 4.8 / GPT-5.5), one agent holding full context out-performs parallel
subagents for *logic-critical* work: a subagent returns a **summary**, and summaries drop the
specific detail that logic and security verdicts ride on. Bootoshi's field report and the Harness
Engineering article both land here. But this is not "never fan out": read-only breadth — locating
files, sweeping naming conventions, recon enumeration — is exactly what subagents are good at
(they compact large research well). The existing `/attack` routing fans hunters out; that is correct
*for discovery*. The risk is letting a subagent's summary of code stand in for reading the code when
a verdict or a fix depends on its exact logic — which is also an untrusted-content propagation vector
(a subagent reading attacker-controlled code can be injected and return a poisoned summary; ADR-0002/0003/0006).

## Decision

Fan out (parallel subagents) only for read-only discovery that returns **locations/candidates**.
Run logic-critical reasoning — `/audit` verification, security verdicts, and any implementation or
fix of logic-critical code — in a single context-holding thread. Never substitute a subagent's
*summary* of code for the code itself when the decision depends on that code's logic.

## Invariants

1. `/attack` hunters MAY run in parallel but return candidate **locations**, not trusted logic-summaries.
2. `/audit` verification (`fp-check`) and finding verdicts are single-thread.
3. Subagent output is a **pointer, never evidence**: before any verdict or fix, the single-thread agent re-reads the actual code itself. A subagent's claim ("this function is vulnerable because…") is treated as an unverified lead, which also contains any injection it may have ingested.
4. Implementation/refactor of logic-critical code is single-thread; subagents may gather context but the editing agent reads the actual code it changes.


## Invariant 3 has a mechanical reason too — Addendum 2026-09-06 (append-only)

**Nothing above is corrected. This adds evidence for invariant 3 that the original did not have.**

As written, invariant 3 rests on a *contextual* argument: a subagent returns a summary, and summaries
drop the detail a verdict rides on. True, and it was the only reason given. Measured 2026-09-06 there
is a second, harder one:

> **A subagent may be running a smaller model than you are — chosen by Claude Code, not by you, and
> invisible at the call site.**

**Evidence.** `claude-code-guide` was spawned to answer a documentation question. It ran **9 turns,
all on `claude-haiku-4-5-20251001`**, while the calling session was on `claude-opus-5`. Counted by
parsing `message.model` out of the subagent transcript — not by string-matching, which would have
counted this session's own prose *about* Haiku (the same mention-vs-use error LESSONS 22 and 42
record, and it was made once during this very investigation before being caught).

| | |
|---|---|
| where it is visible | `~/.claude/projects/<session>/subagents/agent-<id>.jsonl`, field `message.model` |
| where it is **not** visible | the call site, the parent transcript, `settings.json`, the agent listing |
| this machine's `settings.json` | `model: opus`, no small-model override, no `env` block `[measured]` |
| population | 73 `claude-haiku-4-5` records against 13,564 `claude-opus-5` across every transcript on this machine; **9 of the 73 are that single spawn** |

`claude-code-guide` has **no definition file on disk** — it is built in, so the model is the
product's choice. On-disk agent definitions mostly declare `model: inherit`; `let-fate-decide:draw`
pins `haiku` deliberately. Neither surface tells you what a built-in will use.

**Why this strengthens rather than restates the invariant.** "A summary drops detail" is a judgement
about quality and invites the reply *"this summary looks thorough."* "The responder was a different,
smaller model and you cannot tell from here" is a fact about the mechanism, and it does not soften
when the output reads well. The output that prompted this was hedged and thin — which was read as
*the honest answer to a hard question* until its model was checked.

**The operational addition, and it is small:** when a subagent's answer is load-bearing, check its
model before weighting it. One parse of one file. This does not change what invariant 3 requires —
re-read the code yourself before any verdict — it removes a reason to think an exception is safe.

**What this does NOT establish.** Nothing here says whether Claude Code makes *other* internal model
calls — compaction summaries, title generation, ranking. That is undocumented, the transcript is not
guaranteed to record such calls, and the authoritative test (`OTEL_LOGS_EXPORTER=otlp
OTEL_LOG_RAW_API_BODIES=file:...`) **was not run** `[assumed absent, unverified]`. Do not read the
73-of-24,103 figure as coverage of internal calls; it covers what transcripts record, which is a
different set.
## Enforcement

- `gavel` / semantic — no static rule; this is a routing discipline.
- CLAUDE.md routing rule (invocation rule 3) states the fan-out-vs-single-thread boundary.

## Consequences

Costs some wall-clock parallelism on the verify/fix path. Buys accuracy where it matters most —
fewer summary-induced misses in audit verdicts, fewer "fixed X, broke Y" regressions from a subagent
that never held full context, and one fewer place for injected untrusted content to propagate as a
trusted summary. Discovery stays fast (still parallel).

## References

- Routing: `~/.claude/CLAUDE.md` (invocation rule 3)
- Related: ADR-0002 / ADR-0003 / ADR-0006 (untrusted-content defenses — the re-read rule is also their ally)
- Source: `docs/ideas/Booworkflow gold.md` (mega-thread vs subagent), `docs/ideas/Harness engineering.md`
- Grill: light `grill-with-threat-model` pass, 2026-06-12 — break path "logic claim dressed as a candidate / injected summary" folded into invariant 3.
