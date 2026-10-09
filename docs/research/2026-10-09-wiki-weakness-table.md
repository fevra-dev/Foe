# Wiki weakness table — pre-work (i), (ii), (v)

Answers the three research items from PICKUP "Next actions" 1. Items (iii) spec addendum and (iv) the
grill come next and depend on the licence decision below.

## (ii) Data source — the wiki's Bucket API, not page scraping

`[measured 2026-10-09]` The OSRS Wiki exposes infobox data through `api.php?action=bucket`. The
`infobox_monster` bucket (schema: `Bucket:Infobox_monster?action=raw`) has, among others:

| field | type |
|---|---|
| `page_name`, `version_anchor` | TEXT (a variant's tab, e.g. `Level 86`) |
| `id` | TEXT, **repeated** — every NPC id that variant covers |
| `elemental_weakness` | TEXT |
| `elemental_weakness_percent` | INTEGER |

```
curl -s -A "<UA>" --get https://oldschool.runescape.wiki/api.php \
  --data-urlencode action=bucket --data-urlencode format=json \
  --data-urlencode 'query=bucket("infobox_monster").select("page_name","version_anchor","id","elemental_weakness","elemental_weakness_percent").limit(500).offset(N).run()'
```

One row per variant, each carrying its own id list. Fire giant, for example, returns three rows,
and the `Level 86` row covers ids 2075–2084. So keying by NPC id falls straight out of the source.
`select("*")` is refused (`Invalid field name: *`), so fields must be named.

### What the whole population looks like `[measured 2026-10-09]`

7 pages of 500 → **3,275 rows**, 4,502 (id, row) pairs, 4,270 distinct ids.

| value of `elemental_weakness` | rows |
|---|---|
| null (no weakness recorded) | 1,965 |
| `Fire` / `fire` | 503 / 18 |
| `Earth` / `earth` | 353 / 43 |
| `Water` / `water` | 193 / 1 |
| `Air` / `air` | 183 / 11 |
| `None` (literal string, no percent) | 5 |

The generator has to handle these quirks, and each one is a test case:

1. **Mixed case.** Normalise the case. Do not drop the lowercase rows: there are 73 of them.
2. **Literal `None`, no percent** (Kraken, Cave kraken, Enormous Tentacle, White jaguar, Dire Wolf
   Alpha). This is a positive "no weakness", which is different from null (unknown). The table can
   carry it as `NONE`, which `WeaknessStore` already has.
3. **Percent outside 1–100:** Spiritual mage/ranger/warrior (Zaros) at **200**, Ice demon at **150**,
   Dinky the drink troll at **0**. Decide whether to display >100 as-is (it's real: those monsters
   take extra) and whether 0 means "none". Either way, the renderer must not assume ≤100.
4. **Non-numeric ids** like `beta14278` (beta-world NPCs). Skip them.
5. **One weakness row with no ids** (King Black Dragon (Echo)). It can't be keyed, so skip it.
6. **15 ids where rows disagree.** 14 are Deadman variants, where the `Apocalypse` tab has no
   weakness and the other tabs do. One is real game state: **Maggot King** id 15742 is fire 5% when
   `Nearby` and fire 80% when `Far`/`Roaring`. The same NPC id carries two percentages depending on
   a mechanic, so a static table can't be right for it. Rule: **on conflict, emit no entry for that
   id**, and in-game learning covers it. The generator should print the conflict list so it gets
   reviewed rather than silently resolved.

## (i) Licence — CC BY-NC-SA 3.0

**Operator decision 2026-10-09:** take the conservative path (a separate CC BY-NC-SA 3.0 notice for the
data file plus a wiki credit in the README), and show percents exactly as the wiki gives them, including above 100.
Recorded in spec addendum 7.

`[documented]` *"Content on this site is licensed under CC BY-NC-SA 3.0; additional terms apply"*
(RuneScape:Copyrights → meta.weirdgloop.org/w/Licensing). Attribution is *"a hyperlink (where possible)
or URL to the page or pages you are re-using"* or a list of authors. The additional term only waives NC
for Jagex.

What that means for us, stated as questions rather than conclusions (not legal advice):

- Foe's code is **BSD-2**. A bundled table derived from the wiki would be **CC BY-NC-SA**, and
  ShareAlike attaches to the table, not to our code. The usual shape is a separate licence notice
  for the resource file (e.g. `src/main/resources/.../LICENSE-weakness-data` plus a README credit
  linking `oldschool.runescape.wiki`).
- Arguably a weakness type and percentage are **game facts set by Jagex**, not wiki authorship. Facts
  aren't copyrightable in the US, but a database's selection and arrangement can be, and in the
  EU/UK there's a database right. I'm not going to decide this. Choosing attribution + CC BY-NC-SA
  for the data file is the conservative path, and it costs one file.
- NC: Foe is free and on the Plugin Hub. No commercial use is planned.

## (v) Plugin Hub — bundled resources are fine; a build-time network generator is not

`[documented — runelite/plugin-hub README, cloned 2026-10-09]`

- **Resources are allowed:** *"Resources may be included with plugins … by placing them in
  `src/main/resources`"*. Load with **`getResourceAsStream`**, never `getResource` (the jar isn't
  unpacked).
- **Build type:** *"In `standard` mode, your `build.gradle` and `settings.gradle` get replaced when built
  during plugin submission."* `runelite-plugin.properties` has **no `build=` line**, so Foe builds as
  standard `[assumed — README does not state the default]`. **A Gradle task that fetches the wiki
  during the build would not run on the Hub,** and `gradle` mode costs "significantly" longer review.
- **Review bar:** *"If it is difficult for us to ensure the plugin isn't against the rules we will not
  merge it."*

**Consequence for the design:** "generated at build time" should mean **generated by a script a
developer runs, with the output committed to `src/main/resources`**. The Hub build stays standard and
offline. Every table update is a reviewable diff. And there is still no network at runtime. The
generator lives in the repo (e.g. `scripts/`) but isn't part of the Gradle build. Its output header
should record the source, the date and the row/id counts, so a reviewer can re-run it and diff.
