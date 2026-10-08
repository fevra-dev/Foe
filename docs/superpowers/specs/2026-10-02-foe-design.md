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
- **Known limit of the full bar before the first hit (settings-redesign review, 2026-10-08).** Foe
  remembers each NPC's last bar for as long as that NPC stays in the scene, so switching targets and
  back keeps the last real reading. But a monster that was damaged *before* Foe saw a bar on it (by
  another player, or before the plugin started) is still drawn as a full bar until its first visible
  bar. The stale style is the only signal of that.
- **Faded applies to the bar text, not to its outline.** With HP text inside the bar, the text gets a
  1px black outline so it reads over both fill and track. Under Faded only the white fades, and the
  outline stays solid, because a faded outline would make stale text unreadable.

## Addendum 4 — 2026-10-08, weakness credited only to a confirmed spell impact; display honesty (operator decisions)

Appended, not edited in place. It supersedes addendum 2 item 2 (how a weakness is attributed), addendum 3's "Max HP
before the first hit" and its Faded note, and the Marker stale style and One-line default layout. Evidence:
`docs/probe/varp-5536.md` and `docs/probe/raw-task8.txt`; plan Task 8 and its Revision of the same date.

### Where a weakness comes from

Varp 5536 holds the weakness of the last NPC a spell was cast at, and does not say which NPC. In the trace every write
arrived on the same tick as a spell-impact spot-anim (`GraphicChanged`) on the NPC the spell landed on (ticks 508,
738, 770), and other players' impacts on nearby NPCs never changed it. So:

1. A change of varp 5536 is held for the **current tick only**. It is never carried to a later tick, and it is dropped
   on logout or hop. A change that arrives while there is no local player (the logout reset 0, 0, -1) is ignored.
2. Every NPC that got a spot-anim this tick is held too, once each.
3. On `GameTick` the change is credited only if **exactly one** of those NPCs is one the player is fighting (the live
   target, an NPC the player is interacting with, or one interacting with the player), the value decodes, the tick
   saw only one value, and that NPC is a live combat NPC. The entry is keyed by the NPC's **transformed composition
   id**, the form the panel's name and stats come from. Anything else is dropped, never guessed. A dropped change
   writes nothing and erases nothing. A confirmed credit may overwrite an earlier entry.
4. Decoding: 554 fire, 555 water, 556 air (expected, never observed), 557 earth, -1 none, anything else unknown and
   dropped. "None" is a real answer and shows nothing.
5. The cache is in memory for the session. It survives logout and hop, because a type's weakness does not change, and
   is cleared when the plugin stops. Nothing is written to disk. The panel shows the entry for the target's type
   even when the target was only ranged or meleed this fight, and "Show weakness" hides it as before.

### Display

- **Stale HP style: Faded (default) and Hollow.** Marker is removed. A profile that stored `MARKER` reads as the
  default; RuneLite logs a warning each time it reads that value, until the setting is changed once.
- **HP text inside the bar has a drop shadow**, black, one pixel down and right, as RuneLite's own text component draws
  it, instead of addendum 3's 1px outline on all eight sides (it looked rough once antialiased in the real client).
  The shadow stays solid when the text is Faded.
- **Default Layout is Stacked**, listed first in the settings panel.
- **Before the first hit**, with max HP known and no health bar ever seen on this NPC, the panel draws an **empty
  outlined bar** with the max HP as its only text (`35`), under every HP text setting except None, which draws just the
  outlined bar. It never shows `35/35` or `100%`. This replaces addendum 3's full bar in the stale style, which lied
  after a relog (seen 2026-10-08). It means "never seen with a bar", not "never hit": after a relog, or for a monster
  another player damaged first, the same state shows, and that is the honest reading of what Foe knows.

### Known limits (each shows a missing weakness, or the cases noted, never a made-up one)

- **Same-value silence.** The game posts no event when a write leaves the value unchanged, so a spell on a second type
  with the same element teaches nothing; that type shows no weakness until a spell on it changes the value.
- **A splash** (spot-anim 85, no hitsplat) may or may not write the varp: the value was unchanged in the trace, so no
  event could say. A confirmed credit from one would still be the right NPC.
- **An area spell** that hits two NPCs the player is fighting is dropped, even when both are the same type and the
  credit would have been safe.
- **An area spell that also hits an unfought NPC** is dropped when that NPC got the same spell graphic in the same
  tick (Task 8 review F1): which target the varp then holds was never probed, so the credit is not guessed.
- **One coincidence is not caught.** Our write lands on a tick where the only fought NPC with a spell graphic got it
  from another player. The trace has our write on the tick we engage the cast target, which makes this unlikely, and
  the next confirmed cast on that type corrects it.
- **Correction (Task 8 review F3):** the trace shows the write on the tick the cast target is first engaged, with
  the damage hitsplat 3–4 ticks later. Earlier text saying "at impact, not at cast" overstated what the trace shows;
  the graphic and the write share a tick, and that tick is the cast/engage tick.
- **`GraphicChanged` on a spot-anim ending** `[assumed]`: whether the client also posts it then is not known, so an
  NPC with no spot-anim left is not counted as an impact. All 65 events in the trace carried one.
- `NPC.getId()` is the id of the untransformed composition `[assumed]`; the trace logged both and they were equal for
  every NPC in it.
