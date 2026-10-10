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

## Addendum 5 — 2026-10-08: exact HP from hitsplats, and remembered weaknesses (operator decisions)

**Exact HP.**
- The bar gives only a range: for example ratio 22/30 on 85 max HP means 62–64. Hitsplats give exact damage.
- **Tracking:** Foe tracks, per NPC object, `maxHp − Σ damage + Σ heal`, summed over every hitsplat it sees on that NPC (yours and other players'), from the moment it first sees the NPC.
- **Shown value:** the tracked value whenever it lies inside the bar's current `[min, max]` range (`HpEstimate`'s bounds). Otherwise Foe shows the midpoint, as today, and drops the tracked value for that NPC until an exact bar reading (min == max) re-anchors it.
- **Result:** exact when Foe has watched the NPC from full health, and never worse than today's estimate. A relog, a monster damaged before Foe saw it, regeneration or a missed hitsplat all fall back to the midpoint, because the range check rejects the tracked value.
- The memory is dropped on death, despawn, logout and hop, like `HpMemory`.

**Remembered weaknesses.**
- **Storage:** learned entries are saved in RuneLite's own config (ConfigManager, group `foe`) and loaded at startup, so each monster type is taught once, ever.
- **Format and defaults:** they are game data, not account data, so one global store holds them. Malformed entries are ignored. There is no new visible setting.
- **Known limit:** a wrong entry, or one made stale by a game update that changes a monster's weakness, now persists across sessions. Only a confirmed spell credit on that type replaces it.
- **A bundled wiki weakness table stays v2.**

## Addendum 6 — 2026-10-08: corrections to addendum 5, after its review and an in-game run

**Exact HP, the rule as built.**
- The tracked value (`maxHp − damage + heals`) is shown when it lies inside the live bar's `[min, max]`.
- **On a mismatch**, three cases:
  - **A hit landed since the last read:** the bar may lag, because the client can delay a bar update while posting the hitsplat at once. Foe shows the midpoint and keeps the count as it is.
  - **No hit since, and the gap is at most 2 HP:** unseen regeneration. The count moves to the nearest edge of the bar's range, which after one regeneration is the true value. Measured 2026-10-08: six mismatches, every one exactly −1.
  - **No hit since, and a bigger gap:** damage Foe never saw (a relog, or another player before Foe looked). Foe shows the midpoint until an exact bar reading (min == max) re-anchors the count.
- **Stale (remembered) bars** show the tracked value if it lies inside the remembered range, else the remembered midpoint. They never move or reject the count.

**Corrected error bound.** Addendum 5's "never worse than today's estimate" was wrong.
- **What holds:** the shown value is always one the bar allows. When the count is right, it is exact.
- **When the count is wrong** but still inside the range, the error can reach the full range width, while the midpoint is off by at most half of it. On an 85 HP monster the bar's range is about 3 HP; on a 1000 HP boss it is about 35.

**Remembered weaknesses, corrected.**
- The store lives in the **active RuneLite config profile**, not in one global store. A profile switch reloads it on the next tick.
- A saved value at or beyond the 50,000-entry cap is never rewritten, so entries past the cap are never deleted.
- **Two clients at once:** the config file merges key by key, so the whole value is last-writer-wins. One client can drop another's new entries, or revert a correction, until the next confirmed credit.

## Addendum 7 — 2026-10-09: a bundled wiki weakness table, in v1 (operator decisions)

Appended, not edited in place. It supersedes the Constraints line "no bundled monster tables" **for elemental
weaknesses only** (defensive bonuses and a "best style" hint stay v2), and addendum 5's "A bundled wiki weakness table
stays v2". Evidence: `docs/research/2026-10-09-wiki-weakness-table.md`.

**Goal.** Every monster the wiki covers shows its weakness, with the percentage, from the first attack. In-game
learning (addenda 4–6) stays, and wins where it has an answer.

**Source and generation.**
- The OSRS Wiki Bucket API, bucket `infobox_monster`: fields `id` (repeated), `elemental_weakness`,
  `elemental_weakness_percent`, plus `page_name` and `version_anchor` for the report. No page scraping.
- A **developer-run script** in `scripts/` fetches it and writes the table into `src/main/resources/com/foe/`. The
  output is committed. It is **not** a Gradle task: the Plugin Hub's standard build replaces `build.gradle`, so a
  build-time fetch would never run there. The Hub build stays offline, and each table change is a reviewable diff.
- The table's header records the source URL, the fetch date, and the counts of rows, ids written and ids skipped.
- Regenerate it before each release.

**Generator rules** (each one is a test):
1. Element case is normalised (`fire` = `Fire`).
2. The literal `None` is written as NONE: a positive "no weakness", as in addendum 4 item 4.
3. The percent is written as the wiki gives it, including values above 100 (Zaros spiritual monsters 200, Ice demon
   150) and 0. A row with an element other than None but no percent is skipped.
4. Non-numeric ids (`beta…`) and rows with no ids are skipped.
5. **Conflict:** an id whose rows disagree on element or percent gets **no entry**, and the script prints every
   conflict. Measured: 15 ids. 14 are Deadman tabs, and one is real game state: Maggot King, fire 5% or 80% on one id.
   In-game learning covers these.

**Lookup and precedence.** The key is the same transformed composition id the learned store uses.
- **Learned entry present** (a confirmed in-game credit), so it wins:
  - same element as the table → shown with the table's percent;
  - different element → shown with **no percent**, because the table is stale for that monster;
  - learned NONE → nothing shown.
- **Learned entry absent:** the table entry, if any, with its percent.
- **Neither:** nothing, as today.

`Weakness` gains an optional percent. The varp still never carries one.

**Display.** The percent is drawn as given, e.g. `Fire 200%`. The renderer must not assume ≤100.

**Licence.** Wiki content is CC BY-NC-SA 3.0. The data file carries its own CC BY-NC-SA 3.0 notice with a link to
`oldschool.runescape.wiki`, and the README credits the wiki. Foe's code stays BSD-2. The resource is read with
`getResourceAsStream`, never `getResource`, because the Hub jar is not unpacked.

**Known limits.**
- A monster the wiki has not filled in, or has filled in wrong, shows nothing or the wrong value until an in-game
  credit corrects it. The correction then shows the element without a percent.
- `[assumed]` The wiki's `id` values match the transformed composition id the panel uses. For an NPC that transforms,
  the wiki may list the base id. To be checked against the probe trace before building.
- A Jagex rebalance between releases is right only after a regeneration, or a credit, for that monster.

## Addendum 8 — 2026-10-09: fixes for the addendum 7 grill

Appended, not edited in place. It answers `docs/research/2026-10-09-addendum-7-grill.md` (F1–F10) and supersedes
addendum 7 where they differ. Each rule below is a test. The generator **fails** (non-zero exit, nothing written)
rather than skipping wherever a rule says "fails".

**F1 — strict element parse.**
- Generator: trim the field, lowercase it, then accept only `air`, `water`, `earth`, `fire` or `none`. Any other
  value **fails** the run, listing page, tab and value.
- Runtime loader: same set. A bad line is skipped and counted. The loader **never throws** out of `startUp`: any
  failure leaves an empty table and logs a warning (see F6).

**F2 — vandalism.**
- A table is generated **against the previous committed table**. The generator writes a change report: ids added,
  removed and changed, with page and tab. The reviewer reads that report, not the table.
- **Quarantine:** an id whose value changed is accepted only if its wiki page's last edit is at least **7 days old**.
  Otherwise the previous value is kept and the change is listed as pending. Last-edit time comes from
  `action=query&prop=revisions` (50 titles per request). Measured 2026-10-09: 89 of 627 weakness pages were edited
  within 7 days, and 241 within 30.
- **The first generation has no previous table,** so nothing is quarantined. Its report lists every page edited
  within 7 days, and those get a manual check against the in-game Monster Examine before the table is committed.
- **Ceiling:** an edit that survives 7 days of wiki patrol ships. In-game learning corrects the element for anyone who
  casts on that monster.

**F3 — precedence, revised.** Addendum 7's rule stands except for one case:
- **Learned NONE against a table weakness → show the table.** NONE is the weakest evidence the store holds: it is
  never displayed, so a wrong one is invisible, and same-value silence can stop it being corrected.
- **Learned element differing from the table** is still shown, with no percent. A disagreement is the only signal of
  a rebalance the table hasn't caught. A wrong learned element is corrected by the next cast that changes the varp.

**F4 — conflicts.** For each id, compare the normalised `(element, percent)` of every row. A row with no weakness
compares as the value "unknown". Two or more distinct values → **no entry**, and the id appears in the report.
Measured: 15 ids (14 Deadman ids, plus Maggot King).

**F5 — agreeing phases.** Inherent; kept as a known limit. The report lists every id shared by two or more tabs of one
page, even when they agree, so phase monsters get a look.

**F6 — the table can't go missing silently.**
- A unit test loads the **real resource** through `getResourceAsStream`. It asserts:
  - a floor of 1,600 entries (measured 2026-10-09: 1,754 ids with a weakness after conflicts are dropped);
  - known answers: Fire giant 2075 → WATER 100; Kraken 494 → EARTH 50; Whirlpool 496 → NONE; Spiritual mage (Zaros) 11292
    → FIRE 200;
  - Maggot King 15742 → no entry.
- At startup Foe logs `weakness table: N entries` at info level. A failed or empty load logs a warning.

**F7 — partial fetch.** The generator fails on any non-200 reply, a reply without a `bucket` key, or an `error` key.
It walks until it gets an empty page, not just a short one, and drops duplicate rows. It **fails** if the id count
falls more than 5% below the previous table's header count, unless run with `--accept-shrink`. The flag's use is
written into the header.

**F8 — reproducibility, stated honestly.** The wiki can't be re-fetched as it was. The generator saves the raw
Bucket rows as `data/wiki-infobox-monster.jsonl`. That file is committed but **not shipped**: it lives outside
`src/main`, is about 300 KB, and is CC BY-NC-SA. A test re-derives the table from it and asserts byte-equality with
the committed resource. So a reviewer can verify table = generator(raw) offline, and spot-check raw against the wiki.
Addendum 7's "re-run and diff" claim is withdrawn.

**F9 — percent bound.** The generator **fails** on any percent outside 0–999 and lists the offending rows. Measured
range: 0–200. Inside the bound, the percent is shown as given (operator decision, addendum 7).

**F10 — stale percent behind a matching element.** Inherent: the varp carries no percent, so confirmation can't
detect a percent-only rebalance. Kept as a known limit. The release checklist regenerates the table after any combat
rebalance update.

**Still `[assumed]`:** wiki ids equal the transformed composition id for NPCs that transform by varbit. All 6 probe
NPCs match, and none of them transforms. **Task 9 acceptance checks one transforming monster** before release.

## Addendum 9 — 2026-10-09: conflict rule refined; a percent toggle (operator decisions)

Appended, not edited in place. It supersedes addendum 8's **F4** rule and adds one setting.

**Conflicts (replaces addendum 8 F4).** For each id, gather the rows that carry a weakness (an element or `None`).
Rows with no weakness at all are ignored, since a blank tab is unfilled, not a claim.
- **All rows agree on the element and the percent:** emit it.
- **They agree on the element but not the percent:** emit the element with **no percent**. That element is true in
  every phase, and the percent isn't.
- **They name different elements** (counting `None` as an element): **no entry**.
- Every id that hits either of the last two cases is listed in the report.

Measured 2026-10-09 over the full pull: **0** ids name two different elements. Of the 15 former conflicts, the 14
Deadman ids (a blank `Apocalypse` tab) now emit their value, e.g. Earth 35, and Maggot King 15742 emits `FIRE` with
no percent (5 when Nearby, 80 when Far or Roaring). Case normalisation was never the cause: its rows are all `fire`.
Picking the largest percent was considered and rejected. It would show Maggot King at 80% while it sits at 5%.

**Setting: "Show weakness %"** (key `showWeaknessPercent`, default **On**), placed directly after "Show weakness".
- On: `Fire 50%`.
- Off: `Fire`.
- It has no effect when "Show weakness" is off, or when the entry has no percent.

## Addendum 10 — 2026-10-09: generator rules revised after the Task 8b review

Appended, not edited in place. It supersedes addendum 8's F2, F7 and F8 where they differ, and addendum 7's "not a
Gradle task".

**The hold covers every difference (replaces addendum 8 F2's "an id whose value changed").** An id that is
**added**, changed or **removed** relative to the previous table is accepted only when every page that gives it a
weakness now, **or gave it one when the previous table was made**, was last edited at least 7 days ago. Otherwise the
previous state is kept: the previous value, or no entry for an add. Measured: 2,400 numeric ids have no weakness row
today, so filling one in was an add that shipped at once (grill F2's Vorkath case).
- The previous table's pages come from the **previous raw file**, so a page that has since dropped an id still has to
  be old.
- An id with no known page, or a page with no known edit time, is not old.
- A page the wiki reports as **missing** (deleted) counts as old. Deleting a page takes a wiki administrator, and
  holding its ids for ever would be a refusal nobody could clear.
- The 5% shrink check (F7) measures what the wiki says now, before the hold puts anything back.

**Decisions are recorded in the raw file (replaces addendum 8 F8's mechanism).** The raw file's `_meta` line records
the generation (`first` or `diffed`), the previous table's id count, whether `--accept-shrink` was used, and every
held id with the value kept. So table = generator(raw) holds after a run that held something back, not only after a
first generation. `WeaknessTableResourceTest` re-derives with those decisions. The table header gains a
`# Generation:` line, so a run that diffed is distinguishable from one that never could.

**No previous table is a refusal, not a first generation.** The generator refuses to run without a previous table
unless given `--first-generation`, and refuses that flag when a table exists. So deleting the table doesn't switch
the hold off. A previous table that its raw file doesn't re-derive is also a refusal.

**Two walks.** The generator walks the Bucket twice (sorted by `page_name`) and writes nothing unless both walks saw
the same distinct rows. One offset walk over a live database can skip a row without any error.

**Smaller points.**
- An element without a percent takes part in the addendum 9 conflict rule. An id is skipped under rule 3 only when
  none of its rows has a percent, and it is counted once.
- Trimming takes whitespace that isn't a control character.
- The report is written to `data/weakness-report.txt` and committed beside the raw file.
- The generator is a dev-only `JavaExec` task, `./gradlew generateWeaknessTable`, on the test classpath. Addendum 7
  said "not a Gradle task". What it meant, and what holds, is that the build the Hub runs never fetches.

**Still open `[assumed]`:** a weakness value transcluded into an infobox from another page would change the Bucket row
without changing this page's edit time, which bypasses the hold. Not checked.

## Addendum 11 — 2026-10-09: learned-vs-table check; heals indicator deferred (operator decisions)

**Learned-vs-table check.** When the plugin starts, after the table and the learned store have both loaded, Foe
logs at info level `weakness check: N learned, M agree with the table, K disagree` and lists each disagreement as
`id learned=ELEMENT table=ELEMENT [percent]`. A learned NONE against a table weakness is listed too. There is no UI.
Normal play then checks the table at no cost: a full in-game sweep was considered and rejected, because the varp never
carries a percent and same-value silence stops a run of same-element casts from teaching anything.

**A heal indicator (`+12` beside the HP) is a v2 idea.** Heal hitsplats already count into exact HP (addendum 5), so
the HP number rises on the tick a heal lands. What v1 doesn't show is the heal as an event.

## Addendum 12 — 2026-10-09: Task 8c review corrections

- **The learned-vs-table check is skipped when the table didn't load** (missing, unreadable or empty). Foe then
  logs `weakness check: skipped, the weakness table did not load (N learned)` instead of a summary whose `0 disagree`
  would read as a clean result.
- **The summary gains its fourth count:** `N learned, M agree with the table, K disagree, L not in the table`, so
  N = M + K + L and a gap is visible. This supersedes addendum 11's three-count format.
- **"Never throws" means exceptions.** An `Error` from the loader (OutOfMemoryError, LinkageError) is not caught.
  RuneLite then stops the plugin cleanly, and the read is capped at 1 MiB. Marked in code as a deliberate ceiling.

## Addendum 13 — 2026-10-09: form changes are logged; tests stop writing to the player's log

- **Form-change log.** The first time Foe shows an NPC whose shown form (the transformed composition) has a
  different id from the NPC itself, it logs at info level:
  `form change: npc <id> is shown as <form id>; table <id>=<value> is not used, <form id>=<value> is`. Each value is
  `ELEMENT [percent]` or `no entry`. Each pair is logged once, until the next start, login or hop. This makes
  addendum 8's last `[assumed]` (wiki ids match the shown form for monsters that transform) checkable in ordinary
  play rather than by hunting a monster. Measured 2026-10-09: the level-70 candidates (Rock and Sand Crabs) have the
  same value in every form, so they could not discriminate.
- **Tests log to the console.** The RuneLite client jar's `logback.xml` was sending every unit test's log lines into
  `~/.runelite/logs/client.log`, which held 7,838 `[Test worker]` lines on 2026-10-09. The `test` task now points
  logback at `src/test/logging/logback-test.xml`, which is console-only and kept off the classpath so `./gradlew run`
  still logs normally. Measured: a full test run left that count unchanged.

## Addendum 14 — 2026-10-10: the portrait ships (Task 10 spike succeeded)

The spike (`docs/probe/portrait.md`, branch `spike/portrait`, not merged) found a client-only portrait feasible,
which settles open question 2 and the "Show portrait" row of the settings table. Operator decisions, 2026-10-10:

- **What:** the NPC's own models, flat-rendered in software: its chathead models whole, or, when it has none, its
  body models cropped to a square over the top 30% of the model (a head crop). Straight on (yaw 0); 20 degrees was
  tried and was indistinguishable at panel size. Merged, recoloured with the composition's recolour pairs, lit.
- **Where:** inside the panel, left of the name, on the panel's background, with no frame or border. A square as tall
  as the panel's content (panel height minus padding), in both layouts, followed by the usual gap. The panel only
  gets wider.
- **Setting:** "Show portrait", default **Off**, the tenth setting (position 9, after Background opacity).
- **No blank square.** Until the image is ready, or when there is none (no models, a failed render), nothing is
  drawn and no space is kept: the panel widens when the portrait arrives, at most a tick later.
- **Off the client thread.** The spike measured 0.5–16.9 ms to load and light, and 4.6–13.5 ms to render one 64px
  image `[measured]`. The load and light stay on the client thread (they read the client's cache). The render runs
  on RuneLite's shared executor, once per composition id, and is kept: up to 64 images, least recently used dropped
  first.
- **Never throws** into the tick (ADR-0005): any failure means no portrait for that id, and it isn't retried until
  the image is dropped from the cache.
- **Open, `[assumed]`:** whether the image is left-right mirrored. The models tested are near-symmetric.

## Addendum 15 — 2026-10-10: portrait review corrections (supersedes parts of addendum 14)

A fresh-context review of `feat/portrait` found three defects in addendum 14's design. Each is confirmed against the
API sources (`runelite-api-1.13.1-sources.jar`) `[measured]`, and fixed test-first on the branch:

- **"Loading" is not "none".** `Client.loadModelData` returns null "if it is loading or nonexistent", and the two
  can't be told apart. Addendum 14 treated every null as a failure and never retried it, so a model still loading
  on the first fight left that monster type without a portrait for the session. Now a null load is asked again on
  later ticks, up to **10 times** (about 6 seconds with the target shown), and then counts as no model.
- **Colours are cloned before recolouring.** Loaded model data shares its face colours with the client's other
  models, and the API says a mutation "MUST" clone them first (`ModelData.recolor`: "You should call cloneColors()").
  Recolouring without that risked changing how other NPCs look in the game. The merged model is now cloned with
  `cloneColors()` first.
- **"Never throws" includes `LinkageError`.** A RuneLite API that changed under the plugin throws
  `NoSuchMethodError`, which is an `Error`. That escaped the tick, and since nothing was cached it would have recurred
  on every tick. Load and render now also catch `LinkageError`, and the failure is cached. Other `Error`s (out of
  memory) still propagate, as the plugin's startup already chooses.

## Addendum 16 — 2026-10-10: Task 11 review corrections (operator decisions 2026-10-10)

A fresh-context review of the whole plugin (628 tests passing) found two medium defects, several low ones and some
nits. Fixes land on `release/v1`, each test-first and proven by a mutant.

- **Engagement means an attack click (F2; supersedes addendum 2's "combat level above 0" as the Talk-to rule).**
  Talk-to sets the player's interacting target just as Attack does. So talking to a Man, a Guard or any quest NPC
  with stats took the panel, even from a live target mid-fight. Now the player's own interaction adopts an NPC only
  when the player's most recent click **on an NPC** was an attack on that same NPC. An attack is
  `NPC_SECOND_OPTION` (the Attack slot), or `WIDGET_TARGET_ON_NPC` with a magic-spellbook widget selected. That is
  the rule RuneLite 1.13.1's own InteractHighlight plugin uses for "attacked" `[documented: client sources]`.
  - Any other click on an NPC clears the intent (Talk-to, Pickpocket, Trade, using an item on it). Clicks on
    anything else don't touch it, so a prayer flick between the Attack click and the walk-up still counts.
  - The combat-level and not-dying filters still apply.
  - Hits still adopt as before, so auto-retaliate and an NPC that attacks you still bring up the panel.
  - **Known limit:** an attack under a different option slot (a minigame "Fight") adopts on the first hit instead
    of the click.
- **Nothing moves as HP changes (F1).** The HP text beside the bar is measured from every text the target can show
  (unhit max HP, and the setting's text at full HP, each digit at the font's widest), not from the current text.
  Before this, the stacked bar grew from 290 to 309 px as a 1,200-HP monster died, and in one line the cells after
  HP slid left.
- **A count no bar has confirmed is never resynced (F4).** A track starts as an assumption (full health). The
  regeneration resync (addendum 6) now applies only after a live bar has agreed with the count or anchored it.
  Otherwise a mismatch shows the midpoint, as addendum 5 says, until an exact reading anchors it.
- **A twin index can't take the panel (F3).** An NPC that shares the live target's index but is a different object
  (another world view) is ignored by the hit paths, as `gone()` already does. With no live target it's adopted
  like any other NPC.
- **0% is no weakness (F7; supersedes addendum 9's "drawn as given" for 0).** The generator writes an element at 0%
  as NONE, and the loader reads an old table's 0 the same way. One row was affected: Dinky the drink troll (15171).
- **The store's cap counts tokens (F6).** Exactly `MAX_ENTRIES` tokens is "at the cap" and is never rewritten
  (addendum 6).
- **Release text (F8).** The plugin description says Foe replaces Opponent Info. The README is the plugin's own;
  it replaces the repo template.
- **RuneLite pinned to 1.13.1 (F9, ADR-0001).** The version the code's `[measured]` facts were measured on.
- **Accepted, documented (F5).** A client-thread tick still running while the plugin stops can hit a collection
  being cleared. The worst case is one `ConcurrentModificationException`, which EventBus 1.13.1 catches and logs
  (`new EventBus()`, default handler `log.warn`) `[measured: client sources]`.
- **Still open, `[assumed]`:** whether `client.mergeModels(parts)` can return a cached single part, which
  `cloneColors()` would then modify in place (review U1). This couldn't be checked: the method isn't in the
  readable sources.
