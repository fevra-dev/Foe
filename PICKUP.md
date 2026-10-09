# PICKUP — Foe

## START HERE

**State (2026-10-09):** Tasks 1–8, the settings redesign, exact HP and remembered weaknesses are done and merged (PRs #1–#11; `main` at `92556ad`). There are 386 tests. **Next is the wiki weakness table, in v1. Resume at "Next actions" below.**

- **Spec:** `docs/superpowers/specs/2026-10-02-foe-design.md`. Read addenda 1–4 at the end; each later one overrides the earlier ones.
- **Plan:** `docs/superpowers/plans/2026-10-02-foe-v1.md`. Task 8's addendum and its "Revision — 2026-10-08" record the weakness design.
- **Probes:** `docs/probe/varp-5536.md`, `raw.log` and `raw-task8.txt`. They show varp 5536 holds a rune item ID (554 fire, 555 water, 556 air, 557 earth, -1 none), with no percent. It's written on the tick a spell is cast at an NPC, and the impact spot-anim lands the same tick.
- **Routine per task:**
  - Branch off `main`.
  - `implementer` (Sonnet) builds the task, then `reviewer` (Opus) checks it. I re-read the code and fix review findings myself, test-first, and prove each fix with a mutant.
  - The operator pushes (`! git push -u origin <branch>`). I open the PR and wait for CI. The operator merges with `--merge` (the auto-mode classifier blocks the agent from merging).
- **Traps already hit:**
  - RuneLite rejects any `@Subscribe` method not named exactly `on<EventName>`, and the plugin won't start. `FoePluginWiringTest` now checks this by reflection.
  - `block-dangerous-git.sh` matches "git push" even inside heredocs.
  - Up-to-date Gradle tasks run no tests, so use `cleanTest`.
  - `client.log` rolls at 10 MB, so read probe output from `./gradlew run` stdout.
- **In-game smoke test:** `./gradlew run`. Foe's settings are in the RuneLite side panel.

**Next actions, in order:**

0. **Task 8b done 2026-10-09** (branch `feat/weakness-table-generator`: generator, review fixes, spec addendum 10, first table of 1,769 ids). Next is **Task 8c** (runtime load, `resolve()`, percent display, the "Show weakness %" setting). Open: should the generator re-check transclusion on each run (measured clean today, research note); and a possible heal indicator (operator asked 2026-10-09; heal hitsplats already raise exact HP live).

1. **Wiki weakness table (operator decision 2026-10-09: in v1).**
   - **Goal:** every monster shows its weakness, with the percentage, from the first attack.
   - **Shape:** a table generated from the OSRS wiki **at build time**, keyed by **NPC id** (so variants come out right), shipped as a resource. No network at runtime.
   - **Precedence:** an in-game confirmed credit overrides the table, which handles rebalances. Regenerate the table each release.
   - **Status 2026-10-09:** (i), (ii), (iii) and (v) are done (PR #13). See
     `docs/research/2026-10-09-wiki-weakness-table.md` and spec **addendum 7**, which supersedes the list below
     where they differ. The shape changed: the table comes from a **dev-run script whose output is committed**, not
     a Gradle task, because the Hub's standard build replaces `build.gradle`. **(iv) grilled 2026-10-09: 10 findings in `docs/research/2026-10-09-addendum-7-grill.md`; fix pass done as spec **addendum 8**; plan tasks **8b** (generator) and **8c** (runtime) written in the plan's 2026-10-09 addendum. Spec **addendum 9** (2026-10-09) refines the conflict rule and adds "Show weakness %". Next: Task 8b via `implementer`, on branch `feat/weakness-table-generator`.**
     Its open `[assumed]` (wiki ids = transformed composition id) holds for all 6 probe NPCs, none of which transforms.
   - **Do first, before any code:**
     - (i) the wiki content licence and the attribution it requires;
     - (ii) a structured data source (a wiki API or export) rather than page scraping;
     - (iii) a spec addendum, because addendum 1-era v1 said "no bundled monster tables";
     - (iv) a grill of the design;
     - (v) how Plugin Hub reviewers treat bundled data files and build-time generators.
   - **Settled already:** `WeaknessStore`/`WeaknessLearner` handle in-game learning, spec addenda 4–6 and plan Task 8 cover it, and exact HP is in `HpTracker`.
   - **Rejected (2026-10-09):** a same-name fallback (a confident guess, wrong on exactly the variants that matter), and reading other plugins' data (fragile, and inherits their errors).
2. **Task 9: in-game acceptance.** Run through every setting value with screenshots. Most of it was already exercised on 2026-10-08.
3. **Task 10: portrait spike.** Optional and time-boxed. The portrait setting is added only if it works.
4. **Task 11:** a fresh-context review, then the release PR and the Plugin Hub.

**v2 ideas (not v1):** a multi-target "Foe list" for multi-combat, and the `first-peasant-view` HUD reusing `HpEstimate`/`HpMemory`.

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
