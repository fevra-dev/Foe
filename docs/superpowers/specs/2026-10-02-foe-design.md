# Foe — monster target panel, v1 design

**Status:** approved in brainstorm 2026-10-02, pending spec review
**Scope:** a RuneLite plugin overlay showing real-time info about the monster you are fighting.
Party frames are a later, separate spec (layout picks recorded in project memory).

## Goal

One glance tells you: what you are fighting, how much HP it has left, its combat levels, and what
element it is weak to. Nothing else. Design brief: Dieter Rams, "less, but better" — every element
must answer a question a player asks mid-fight, and every optional element can be switched off.

## Constraints

- **Client-only.** No network calls and no bundled monster tables. Defensive bonuses (stab/slash/
  crush/magic/ranged) and a "best style to use" hint therefore wait for v2.
- Plugin Hub compatible, built from RuneLite's official `example-plugin` template (Java 11, Gradle).
- Name: **Foe** (Plugin Hub id `foe`, unclaimed among 2,833 entries on 2026-10-02 `[measured]`). Discoverability comes from tags: `target, monster, npc, weakness, opponent, hp, combat`. The future party-frames plugin is its sibling, **Ally**.
- Prior art `[measured]` (README read): *Better Monster Examine* (wiki-fetched stats, search panel), *Elemental Weakness Checker* (right-click, wiki lookup), *Combat Glance* (your own style and prayers). Foe differs by being passive, live and offline.
- Replaces the built-in Opponent Info overlay. Players running both get duplicate info, and the
  plugin's description says so.

## Data sources

Checked against RuneLite `master` at `d8e7d1e` (2026-09-30), from a shallow clone `[measured]`:

| Field | Source | Notes |
|---|---|---|
| Name, combat level | `NPC.getName()`, `NPCComposition.getCombatLevel()` | |
| Max HP | `NPCComposition.getStats()[STAT_HITPOINTS]`, falling back to `NPCManager.getHealth(id)` | Both methods exist `[measured]`. Whether `getStats()` is filled in for every monster is `[assumed]`, which is why there is a fallback. |
| Current HP | `Actor.getHealthRatio()` / `getHealthScale()` × max HP | The game sends only the bar fraction, so the number is an **estimate** and is shown as one. Ratio is `-1` when no bar is visible. |
| Att / Str / Def / Rng / Mag | `NPCComposition.getStats()` indices `STAT_ATTACK`=0, `DEFENCE`=1, `STRENGTH`=2, `RANGED`=4, `MAGIC`=5 | `[measured]` constants |
| Elemental weakness | VarPlayer `LAST_NPC_ELEMENTAL_WEAKNESS` = 5536 | Exists `[measured]`. **How it encodes element and percentage is `[assumed]` unknown** and must be probed in-game before `WeaknessDecoder` is written (plan Task 1). |
| Portrait | — | The client has `createItemSprite` for items and `loadModel` for raw models, but **no NPC-to-image renderer** `[measured]`. Feasibility is a spike (plan Task 1). If it can't be done client-only, the portrait setting does not ship in v1, and the plugin says so rather than showing a blank square. |

## Target selection (`TargetTracker`)

1. Target = the NPC the local player is interacting with (`getInteracting()` is an `NPC`).
2. Otherwise, the most recent NPC that is interacting with the local player and has dealt a hitsplat.
3. After combat ends the target stays shown for `lingerSeconds` (default 10). It clears straight away
   on the NPC's death or despawn.
4. One target only.

Pure logic with no rendering, so it can be unit-tested with fake events and a fake clock.

## Layout (`TargetOverlay`)

A single horizontal strip, default position top-left, using RuneLite's standard overlay background
and font so it matches every other overlay. No ornament.

```
[portrait] Ice Giant 53 │ ██████████░░ ~52/70 │ Att 40 Str 40 Def 40 │ Fire 100%
```

Segments are drawn only when they have data and are enabled. A missing segment collapses — no
empty boxes and no placeholder dashes in the strip itself.

## Settings (`Config`) — each one removes something

| Setting | Default | Options / effect |
|---|---|---|
| Detail | Full | **Compact**: name, HP, weakness. **Full**: also combat levels. |
| Show portrait | Off | Only if the Task 1 spike succeeds. |
| HP display | Number | **Number** (`~52/70`), **Percent** (`74%`), **Bar only**. |
| Hide irrelevant levels | On | Hides any level ≤ 1. A monster that doesn't use Ranged or Magic doesn't show them, so Ice Giant shows Att/Str/Def only. |
| Show combat level | On | |
| Show weakness | On | |
| Linger after combat | 10 s | 0–60 s. |
| Background opacity | Standard | 0 % gives floating text only. |

Seven settings and one conditional one. Nothing cosmetic beyond opacity: no themes, colours or skins.

## Missing data

Never show an invented value:

| Missing | Behaviour |
|---|---|
| Max HP unknown | Bar only, no number, regardless of the HP display setting |
| Health ratio `-1` | Last known value, dimmed. If there is none, the HP segment is hidden. |
| A stat is 0 or absent | That level is hidden |
| Weakness varp 0 or undecodable | Weakness segment hidden |
| NPC name null or "null" | Panel hidden |

## Units

| Unit | Job | Depends on | Test |
|---|---|---|---|
| `TargetTracker` | Who the target is and when it expires | Events and a clock (injected) | Unit tests |
| `TargetSnapshot` | Immutable record of the displayed values | — | — |
| `SnapshotFactory` | Builds a snapshot from an NPC plus the client | `Client`, `NPCManager` | Unit tests with mocks |
| `WeaknessDecoder` | varp 5536 → (element, percent) or empty | — | Unit tests, written **after** the Task 1 probe gives the real encoding |
| `TargetOverlay` | Drawing only; reads one snapshot | Config | In-game screenshot at each detail level |
| `FoePlugin`, `FoeConfig` | Wiring and settings | all of the above | Run in the dev client |

## Testing

- JUnit for the tracker, factory and decoder. Each new test is shown to fail without its fix.
- In-game acceptance, by screenshot (the screenshot is the test, not the computed state): Ice Giant
  (fire weakness), a monster with no weakness, a monster with Ranged/Magic levels, death mid-fight,
  linger expiry, every value of every setting.

## Out of scope for v1

Defensive bonuses, best-style hint, max hit, attack style or speed, multiple targets, party frames,
any network call.

## Open items resolved by plan Task 1 (spike)

1. Encoding of varp 5536. Method: log its value against several monsters with known weaknesses.
2. Whether a client-only NPC portrait is feasible. Candidate: an off-screen model widget, or a
   software render of `loadModel` output.

## Repo note

`~/Apps/Runelite/Party` is not a git repository yet, so this spec is uncommitted. Initialising it
(`/macdaddy` seeds the gate and ADRs) is a plan step and needs operator sign-off.

## Addendum — 2026-10-02, from the planning threat-model pass (plan Task 0)

Appended rather than edited in place, so the original decisions above stay readable.

1. **Target selection is sticky (amends rule 2).** In multi-combat several NPCs hit you each tick, so
   "the most recent NPC that hit you" would flicker. A hit from another NPC is adopted only when
   there is no live target. Attacking an NPC yourself always wins.
2. **Only combat NPCs count.** `getInteracting()` is also set by Talk-to, so an NPC is a candidate
   only if its composition's combat level is above 0.
3. **NPC names are markup.** Names can carry `<col=…>` tags, so they pass through
   `Text.removeTags` (`net.runelite.client.util.Text`, exists `[measured]`) before they are drawn.
4. **Varp 5536 is "last NPC", so it may be stale.** It can still hold the previous monster's
   weakness when a new target is engaged. The probe (plan Task 1) must establish when it updates,
   and the decoder must not show a value that predates the current target.
5. **The encoding cannot be read from the game's own scripts.** No client script in
   `Joshua-F/osrs-dumps` (`fc15240`, 2026-09-30) reads `last_npc_elemental_weakness`: the target
   name appears in 0 of 9,915 scripts, against a control (`%option_run`) appearing in 2
   `[measured]`. The value is server-set and only an in-game probe can decode it.

## Addendum 2 — 2026-10-03, from the in-game probe and operator decisions

1. **Weakness is element only.** varp 5536 holds the elemental rune's item ID (554 fire, 555 water,
   556 air, 557 earth), and -1 means none. The client never receives the percent: varp 5537 was probed
   and holds 0. It is set only by a spell that lands, and it resets on logout
   (`docs/probe/varp-5536.md`).
2. **Where the weakness comes from.** It is shown when it was set on the current target, matched to
   the NPC targeted on the tick of the change. It also comes from a session-only, in-memory cache keyed
   by NPC id, so later fights with that type show it even when ranged or meleed. Nothing is written to
   disk. Persistence waits for v2.
3. **HP display gains "Number and percent"**: bar + `~52/70 (74%)`. The setting now has four
   options: Number, Percent, Number and percent, Bar only.
4. **New setting: Stale HP style.** It controls how HP is drawn when the live bar is missing and the
   last known value is shown:
   - **Faded** (default): bar fill and HP text at half opacity.
   - **Hollow:** bar outline only.
   - **Marker:** a `?` after the HP text.

   This takes the settings to eight, plus the conditional portrait setting.
5. **Settings are clamped in code.** `@Range` is enforced only by the settings spinner (client
   1.13.1), so the overlay and plugin clamp opacity (0–100) and linger (0–60 s) themselves.

## Addendum 3 — 2026-10-04, after the first in-game run of the wired plugin (operator decisions)

The settings were redesigned "less, but better": two new capabilities, two settings removed, and
eight settings in total.

| Setting | Options (default first) | Replaces |
|---|---|---|
| Layout | **One line**, Stacked (bar under the name) | new |
| HP text | **Current/max** (`50/100`), Current (`50`), Percent, None | HP display |
| HP text position | **Beside bar**, Inside bar (centred on the bar) | new |
| Detail | **Full**, Compact (hides the levels) | unchanged |
| Stale HP style | **Faded**, Hollow, Marker | unchanged |
| Show weakness | **on** | unchanged |
| Linger after combat | **10 s** | unchanged |
| Background opacity | **61%** | unchanged |

- **Removed: "Number and percent"** (two readings of one value) and **"Show combat level"**. The level
  is always shown next to the name, and Compact does not hide it.
- **"Hide irrelevant levels" is now always on.** Levels of 1 or less are never drawn.
- **No `~` before the HP number.** This matches RuneLite's Opponent Info; the bar already conveys
  that the value is approximate.
- **No "Lvl" label.** A number after a monster's name already reads as its level in OSRS.
- **Max HP before the first hit.** A monster has no health bar until it takes damage (seen on a
  Kalphite Soldier). When max HP is known and there is no bar yet, Foe shows a full bar drawn in the
  stale style, with the max HP as its text, until the first live reading.
