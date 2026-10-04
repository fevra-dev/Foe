# PICKUP — Foe

## START HERE

**State (2026-10-03):** Tasks 1–6 done and merged (PRs #1–#7; `main` at `c4fc0fc`). Branch `feat/wiring` was created for Task 7. **Resume at Task 7.**

- Spec: `docs/superpowers/specs/2026-10-02-foe-design.md` (read the addendum at the end).
- Plan: `docs/superpowers/plans/2026-10-02-foe-v1.md`. Tasks 0–11. Tasks 0–1 are done.
- **Branches are stacked:** `main` ← PR #1 `plan/foe-v1` (CI green: `lint-gate` and `build` SUCCESS on `4c0c60b`) ← `feat/skeleton-probe` (pushed, no PR yet) ← `feat/target-tracker` (Task 2).
- Execution mode: **subagent-driven**, one task per spawn. `implementer` writes the task, then `reviewer` reviews it. Both agents load in a fresh session `[measured 2026-10-03]`.
- **Pushing is blocked for the agent** by `block-dangerous-git.sh`. That hook also matches the words inside heredocs, so write files with Edit/Write instead. The operator pushes with `! git push origin <branch>`. Name the branch, because the checkout moves between branches.

**Probe results** (`docs/probe/varp-5536.md`):
- varp 5536 is the **elemental rune's item ID** (554 fire, 555 water, 557 earth), and -1 means no weakness. **There is no percent.**
- It's set by **spell casts only**, not ranged or melee.
- It resets on logout and survives region loads.
- It's int-typed, so `getVarpLongValue` throws.
- The player's `getInteracting()` flips to null between ticks, which backs the sticky tracker.
- `client.log` rolls at 10 MB, so read probe output from `./gradlew run` stdout.

**Next actions, in order:**

1. **Routine per task:**
   - Branch off `main`.
   - `implementer` builds the task, then `reviewer` checks it, and I fix whatever the review finds.
   - The operator pushes. I open the PR and wait for CI, and the operator merges with `--merge`.
   - The auto-mode classifier blocks merges by the agent.
   - Done: Task 3 `HpEstimate` (PR #4), Task 4 snapshot types (PR #5), Task 5 `FoeConfig` (PR #6), and Task 6 `FoeOverlay` (PR #7). Task 6 added the Stale HP style setting and Number-and-percent (spec addendum 2). 86 tests.
2. **Task 2** is done: 17 tests, the reviewer approved, and 12 mutations were caught. The review's must-fix (an in-flight hit stealing a new target) was fixed with `playerHit` in `1652f2c`. Its wiring findings are now amendments at the top of plan Task 7.
3. **Task 7 (wiring) is next.** Its code in the plan predates nearly everything, so **amendments 1–10 at the top of Task 7 override it**, together with spec addendum 2. The highlights:
   - `playerHit` for your own hits;
   - one `now` per handler, from a monotonic clock;
   - a per-target last-known HP that sets `hpStale`;
   - the new `SnapshotFactory.build` signature;
   - linger clamped to 0..60;
   - keep `@Getter(AccessLevel.PACKAGE) volatile TargetSnapshot snapshot`, which `FoeOverlay` reads.
   
   Task 8 (weakness decoder, element-only, with the session cache by NPC id) is re-planned from `docs/probe/varp-5536.md`.
   - `lingerSeconds = 0` blinks on null-interacting ticks.
   - Use a monotonic `now()`: `System.nanoTime()/1e6`, not `currentTimeMillis`.
   - The plan's `onHitsplatApplied` picks the first NPC interacting with the player, which may not be the hitter.
4. **Task 8 re-plan.** Write a spec addendum first. **Operator decision (2026-10-03):** show the weakness **element only**, in two cases:
   - when it was set on the current target, matched to the NPC targeted **on the tick of the change**;
   - from a **session-only, in-memory cache keyed by NPC id** (not name), so later fights with that type show it even when ranged or meleed.
   - Nothing is written to disk, so this fits the spec's "no file I/O". On-disk persistence waits for v2.
   - **There is no percent.** varp 5537 was probed and holds 0.

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
