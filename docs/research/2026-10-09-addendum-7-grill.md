# Grill — spec addendum 7 (bundled wiki weakness table)

Hostile pass, third person. It finds break paths and proposes no fixes; the fixes are a separate pass.
Evidence is the full Bucket pull of 2026-10-09 (`docs/research/2026-10-09-wiki-weakness-table.md`) and the
probe traces.

**Id assumption, checked first** `[measured]`: all 6 NPC ids in `docs/probe/raw*.{log,txt}` (7278, 2103, 3025, 10,
242, 265) are present in the wiki data, and id == tid for every one. **None of them transforms**, so this says
nothing about multi-form NPCs (the only case the `[assumed]` is about). Kraken/Whirlpool and the Zulrah forms are
separate NPC ids on the wiki (494/496, 2042–2044), so NPC swaps are fine. Varbit-driven composition transforms are
still unprobed.

---

### F1. Unknown element strings have no rule
- **Mechanism:** Rule 1 normalises case only. The field is free TEXT written by any wiki editor, and the spec doesn't
  say what happens to `Fire ` (trailing space), `Fire (melee)`, `Dragonfire`, or a new element.
- **Impact:** depending on the implementer, the plugin crashes on table load (`Element.valueOf` throws at startup), or
  a value maps to the wrong element, or it's dropped silently.
- **Exploit scenario:** one edit to one infobox before a regeneration → the committed table carries the string → the
  table loader throws in `startUp` → Foe fails to start for every user.

### F2. Wiki vandalism ships as a confident answer
- **Mechanism:** the source is publicly editable, and the only gate is a human reading a diff that touches up to ~2,300
  ids. A single-row edit is invisible in it.
- **Impact:** a wrong weakness shown with a percent, so it looks authoritative. That's the "confident guess" the
  same-name fallback was rejected for, arriving through a different door. In-game learning only corrects it for
  players who cast on that monster, and then the percent disappears.
- **Exploit scenario:** an editor sets Vorkath to `Water 100` an hour before release regeneration → the diff shows one
  changed line among hundreds → it merges → every Vorkath shows Water 100%.

### F3. The precedence rule makes a wrong learned entry costlier than before
- **Mechanism:** "learned wins" assumes in-game credits beat the wiki. Addenda 4–6 list how a learned entry can be
  wrong (the other-player coincidence, two-client last-writer-wins), and addendum 5 makes it persist forever. Before
  the table, a wrong learned entry replaced *nothing*. Now it replaces a *correct* table answer.
- **Impact:**
  - A wrong learned element overrides a right table entry and erases its percent.
  - A wrong learned NONE hides a right weakness. NONE is never displayed, so the player can't even see that
    something is suppressing the table.
- **Exploit scenario:** the coincidence case credits NONE to Fire giant once → the table's `Water 100%` never shows
  again on that profile, and no confirmed credit can replace it unless the varp *changes* on a Fire giant cast
  (addendum 4's same-value silence).

### F4. A null row in a conflict is undefined
- **Mechanism:** rule 5 says "rows disagree on element or percent", but doesn't say whether a row with **no**
  weakness counts. The measured 15 conflicts include null rows: 14 Deadman ids have an `Apocalypse` tab with no
  weakness.
- **Impact:** one reading drops all 14 ids. The other, which skips nulls first, keeps the value and silently resolves
  a tab whose state really differs. The two implementations disagree, and the spec can't decide between them.

### F5. Rows that agree but describe different states pass as clean
- **Mechanism:** conflict detection only catches disagreement. Maggot King was caught because its tabs differ. A
  monster whose phases share an id **and** happen to share a value, or whose phase change the wiki doesn't model as
  tabs, is emitted as a static truth.
- **Impact:** a correct-looking entry that's right only in some phases. Undetectable at generation time by
  construction.

### F6. A missing or broken table is silent
- **Mechanism:** the spec says nothing about the plugin when the resource is absent, empty or malformed. "No table"
  behaves exactly like v1-before-the-table, so a release with a broken resource looks normal.
- **Impact:** the feature ships dead, and nothing in the plugin, the logs or CI says so (silent-pass Q3/Q10).
- **Exploit scenario:** a renamed resource path, or a `.gitignore` catching `src/main/resources/**/*.tsv` → the jar
  ships without the file → `getResourceAsStream` returns null → every weakness falls back to learned-only.

### F7. A partial fetch commits a smaller table silently
- **Mechanism:** offset pagination over a live wiki has no snapshot. Edits during the walk shift rows, which causes
  duplicates or skips. The probe loop ends on "page < 500" or on any non-JSON reply, and the spec doesn't require the
  script to fail on an API error, a rate-limit page, or a short page mid-walk.
- **Impact:** a truncated table with plausible header counts, committed and released. The counts are self-reported,
  and nothing compares them to the previous release.

### F8. "Reviewers can re-run and diff" is not true
- **Mechanism:** the source changes continuously, so a re-run tomorrow gives a different table. The header's source
  and date are written by the script, and nobody can verify them after the fact.
- **Impact:** the committed file can't be tied back to what the wiki said. A hand-edited table with an honest-looking
  header is indistinguishable from a generated one. That matters for the Hub's "if it is difficult for us to ensure…"
  bar and for F2.

### F9. The percent has no bound
- **Mechanism:** "show as given" plus an INTEGER field means negative or 5-digit values pass rule 3. The spec only
  says the renderer must not assume ≤100.
- **Impact:** layout overflow or a nonsense display (`Fire -50%`, `Fire 99999%`) driven by one wiki edit. Same
  vector as F2, aimed at the renderer.

### F10. Stale percent behind a matching element
- **Mechanism:** a learned element that equals the table's element shows the **table** percent. A rebalance that
  keeps the element and changes the percent is invisible to in-game learning, since the varp carries no percent.
  So the stale table percent is shown with the learned entry's authority behind it.
- **Impact:** a wrong percent until the next release, made more credible by the in-game confirmation. The addendum
  lists "rebalance between releases" as a limit, but not that confirmation can't detect it.

---

**Not found (named so it isn't re-attacked without varying it):** no runtime network surface (offline, resource in
the jar); no injection path from the data file into code (the parsed fields are an int id, an enum and an int). That
holds only if F1's parse is strict, which is unspecified.
