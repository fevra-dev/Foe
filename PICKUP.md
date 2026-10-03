# PICKUP — Foe

## START HERE

**State (2026-10-02):** design approved, plan written, no Java yet.

- Spec: `docs/superpowers/specs/2026-10-02-foe-design.md` (read the addendum at the end).
- Plan: `docs/superpowers/plans/2026-10-02-foe-v1.md`. Tasks 0–11; Task 0 is done.
- PR #1 (`plan/foe-v1` → `main`) holds the plan and the spec addendum. `build` passes. **`CI` fails**, for the two harness reasons under "Fix first" — neither is the plan's fault.
- Execution mode chosen by the operator: **subagent-driven**, one task per spawn.
  - Implementer: `~/.claude/agents/implementer.md` (Sonnet 5.5, effort xhigh).
  - Review: `~/.claude/agents/reviewer.md` (Opus 5.5, effort high), after every task.
  - Both files were created mid-session and **weren't detected without a restart**, which is why this handoff exists. In a fresh session, check that `implementer` and `reviewer` appear in the agent list before spawning.

**Next actions, in order:**

1. **Fix first (PR #1's `CI`):**
   - Run `~/.claude/scripts/macdaddy.sh --baseline` to seed `.githooks/gate-baseline`. Its dry-run reports `+ would write: .githooks/gate-baseline` `[measured]`. It also prints a **false** refusal for `.githooks/pre-push` ("ESCAPES THE REPO"). The symlink resolves to `Foe/.githooks/lint-gate.sh` `[measured: realpath]`. Ignore it; that's a macdaddy re-run bug (see Harness defects).
   - `.github/workflows/ci.yml` installs uv and trivy but **not gitleaks** (`grep -c gitleaks` → 0; control `trivy` → 5 `[measured]`). Add a SHA-pinned gitleaks install step (ADR-0018: pin by commit, top-level `permissions:` already present).
2. **Task 1** (skeleton + probe): spawn `implementer` with the Task 1 text verbatim, **Steps 1–3 only**. Branch `feat/skeleton-probe` off `plan/foe-v1`. Create `docs/probe/varp-5536.md` with the Step 5 template unfilled. Commit message: `Add plugin skeleton and varp 5536 probe`. Tell it to:
   - Verify the API names against the *resolved* `net.runelite:client` jar (`latest.release` may differ from master `d8e7d1e`).
   - Null-guard `client.getLocalPlayer()` in the probe.
   - Add `*.log` to `.gitignore`.
   - Report the resolved client version.
   Then spawn `reviewer`.
3. **Operator runs the probe in game** (plan Task 1 Steps 4–5), then fills in `docs/probe/varp-5536.md`.
4. Tasks 2–7, each implemented and then reviewed. Task 8 is a deliberate re-plan point after the probe.

## Decisions already made (don't re-litigate)

- Name **Foe**; the future party-frames sibling is **Ally**. Party layout picks: concepts 2, 16, 21, 24, 26, 30, 31, 32, 33, 34, 35, 36, 39, 40, from `~/Apps/Runelite/Party/runelite_mmorpg_party_frames_plugins.tsx` (viewer: `Party/concepts/party-frames-viewer.html`).
- Client-only for v1. No network and no bundled monster tables, so defensive bonuses and a "best style" hint wait for v2.
- The portrait is a setting, default Off, and ships only if the Task 10 spike succeeds.
- No chevron: a Compact/Full setting instead. Standard RuneLite overlay styling; no themes.
- Target selection is sticky in multi-combat, and Talk-to doesn't count (spec addendum).

## Harness defects found while bootstrapping — for `~/Apps/WORKFLOW`, not this repo

| Defect | Evidence |
|---|---|
| Gate's gitleaks stage refuses the **first push to an empty remote**, and its remedy (`git fetch origin`) can't work there (silent-pass Q13: unreachable remedy) | First push refused; bootstrapped with an operator-approved `--no-verify` after a manual `gitleaks git` (1 of 1 commits, no leaks) and a clean `trivy fs` |
| `pre-push:1325: _GL_CONF: unbound variable` from the EXIT trap when the gitleaks stage exits early under `set -u` | Same push log |
| Seeded `ci.yml` has no gitleaks install, so `CI` fails `[STRICT]` in every new repo | PR #1 `CI` run |
| `macdaddy.sh` on re-run refuses its own in-repo `pre-push` symlink as "ESCAPES THE REPO". **Root cause:** a case-sensitive string compare of paths on a case-insensitive filesystem — it holds the repo as `/Users/fevra/apps/runelite/Foe` (the session's lowercase additional dir) while `realpath` returns `/Users/fevra/Apps/Runelite/Foe` | `--baseline` log line 28 vs `realpath .githooks/pre-push` `[measured 2026-10-03]` |
| Agent files added to an existing `~/.claude/agents/` weren't picked up mid-session, contrary to the docs | Two `Agent type 'implementer' not found` errors about a minute apart |
