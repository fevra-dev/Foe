# Portrait framing: find the head, not the box

Date: 2026-10-15. Status: approved in brainstorming (operator: approach A, a framing-only pass, head and
shoulders). It extends the Foe spec (`2026-10-02-foe-design.md`) as **addendum 23**, and where it differs
from addenda 19–22 it supersedes them.

Why this exists: the operator reviewed the whole `::foegallery` corpus and flagged about 210 bad cells,
grouped by cause in `docs/research/2026-10-15-portrait-review.md`. Most of the causes come back to one
thing: framing reads the model's outer extremes (its bounding box), so a wingtip, horn, spear, spark or
shadow decides the frame instead of the head. Fixing one monster at a time has already regressed others
three times (the frog, the spider, the demons), so this pass changes the rule, and the whole corpus judges it.

## Scope

**In:**
- Transparency, both drawn and ignored when framing.
- Framing from an outline (a coverage grid) instead of the bounding box.
- Head-finding for the UPRIGHT and SIDE views, with a head-and-shoulders frame.
- Trimmed bounds for the whole-frame views.
- A narrower TOP rule.
- No portrait for a flat model.

**Out:**
- The idle pose and texture colours. These cover the black-ribbon bosses (Nylocas, Nightmare, Maiden,
  Hydra, Wyrm, Drake, Araxxor, Zulrah and others), which wait for a probe.
- Turning the model with the mouse, and the v1.1 monster browser. Both are recorded in PICKUP.
- Per-monster overrides.

## Design

### 1. Transparency

- `Portrait.Mesh` gains `transparencies` (`byte[]`, may be null), copied in `PortraitModels.load` from
  `Model.getFaceTransparencies()`. The method is declared on `net.runelite.api.Mesh`, which `Model`
  extends (`javap net.runelite.api.Mesh`, RuneLite API 1.13.1).
- **Drawing.** A face with transparency `t` is drawn with alpha `(255 - t) / 255`.
  - `[assumed]`: 0 is opaque and larger is more see-through. The first gallery run shows whether this
    is right: the Blood Moon's black square either fades or it doesn't.
- **Opaque.** A face is opaque when the array is null or `t == 0`. Framing reads only opaque faces.
- **Ghosts.** If a model has no opaque face, framing reads every drawable face instead, so a model that
  is see-through all over still draws.

### 2. The outline

- The faces used for framing are filled into a 128 x 128 boolean grid over their projected 2D bounds,
  in the view's projection (`across` and `down`, as in `render`). Java2D `fillPolygon` into a small
  binary image is enough; the code stays pure, with no client.
- **Trimmed bounds:** the smallest box holding the outline's filled cells, with 1% of the filled cells
  trimmed from each side on each axis. A few dots far away move it by at most a cell or two.

### 3. Views

`View.of` changes only where marked:

- **CHATHEAD, WHOLE:** unchanged rules.
- **UPRIGHT:** unchanged rule (`height >= LOW * length`).
- **TOP:** *new.* The body is low, `height < FLAT * width` with `FLAT = 0.3`, and
  `length >= SQUARE * width` with `SQUARE = 0.85`. That covers the spiders, krakens, scarabs, Venenatis,
  Kalrag and the fever spider.
- **SIDE:** *new.* Every other low body, whatever its length against its width. That covers birds,
  gryphons, wyverns, cockatrices, frogs, rats and dragons.
- **None:** *new.* A model whose height is 0, or whose outline has no filled cell, renders as null (no
  portrait). `describe` reports it as `flat`.

The measurements that set FLAT and SQUARE come from the gallery's `index.tsv`, as height/width and
length/width:

| Monster | height/width | length/width |
|---|---|---|
| Spiders | 0.16–0.24 | 0.75–0.97 |
| Krakens | 0.21–0.30 | 0.95 |
| Cockatrice | 0.79 | 0.95 |
| Gryphon | 0.34 | 0.64 |
| Giant frog | 0.13 | 0.33 |

### 4. Finding the head

All of this works in outline cells, and the result converts back to model units for the crop.

**UPRIGHT** (front view; rows run top to bottom):

1. **Centre column `c0`:** the column of the outline's centre of mass (mean column of the filled cells).
2. **The central run of a row:** the run of filled cells that contains `c0`. If the cell at `c0` is
   empty, the row has no central run.
3. **Typical width `W`:** the median central-run width over the rows that have one.
4. **Head top:** the first row from the top whose central run is at least `HEAD_MIN = 0.2` × `W`.
5. **Head band:** from the head top down by `W` rows, clamped to the outline.
   - The head's size `h` is the median central-run width in the band.
   - The head's centre `cx` is the mean of those runs' midpoints.

**SIDE** (side view; the front, smaller z, is on the left; columns run front to back):

1. **The run of a column:** its tallest run of filled cells.
2. **Typical height `H`:** the median of those runs over the columns that have one.
3. **Head front:** the first column from the front whose run is at least `HEAD_MIN` × `H`.
4. **Head band:** from the head front back by `H` columns.
   - The head's size `h` is the median run height in the band.
   - The head's top is the smallest run top in the band (both views set the frame's vertical position
     from the head top, so no vertical centre is needed).

### 5. The frame

- **UPRIGHT and SIDE** get a head-and-shoulders square:
  - Its side is `S = SHOULDERS × h`, with `SHOULDERS = 2.2`, clamped so it is no larger than the
    trimmed bounds' larger side.
  - It's centred across on the head: on `cx` for UPRIGHT, and on the band's centre for SIDE.
  - Its top is the head top minus `MARGIN = 0.1` × `S`.
- **WHOLE, TOP, CHATHEAD:** the trimmed bounds, made square around their centre, as today but trimmed.
- **Fallback:** if a head scan finds nothing (no central run, or no column over the threshold), the
  frame is the trimmed bounds, so the portrait shows the whole body.

`HEAD_MIN`, `SHOULDERS`, `MARGIN`, `FLAT` and `SQUARE` are named constants beside `HEAD`, `FRONT`, `LOW`
and `COMPACT`. Their values above are starting points; the gallery loop in Testing tunes them. `HEAD` and
`FRONT` become unused and are removed.

### 6. Unchanged

These keep their current shape: the panel, `PortraitCache` and its debug log line, the prefetch, the
gallery (`index.tsv` columns), the painter's sort, the supersampling and the colour conversion.

## Testing

**Unit tests** (`PortraitTest`): hand-built meshes, no client. Every test is written to fail first, and
every rule is proven by a mutant that the tests kill.

| Case | Expected |
|---|---|
| Upright box body with a tall thin horn | the frame starts below the horn tip and contains the head |
| Upright body with wingtips rising above the head | the head lies in the upper half of the frame |
| Body with dots scattered far off | the frame is the same as without the dots, to within one cell |
| Side-on body with a forward spear | the frame centres on the head, not the spear |
| Side-on body with a tall wing behind the head | the frame contains the head |
| Long thin side-on body (a snake) | the head spans at least a third of the frame |
| View rules | flat square spider: TOP; bird (0.34/0.64), cockatrice (0.79/0.95) and frog (0.13/0.33): SIDE |
| See-through slab in front of the body | the frame is unchanged, and a pixel over the slab is partly transparent |
| All-see-through body | a portrait still renders |
| Height-0 model | null |
| Shape with no head | the frame is the trimmed bounds |

**Acceptance, in game:**

1. Before any code changes, copy `build/foe-gallery/` to `build/foe-gallery-before/`.
2. After the change, `./gradlew run`, `::foegallery`, and compare the sheets before and after.
3. The operator's screenshots in `~/Desktop/FIX - FOE/` are the checklist. Each flagged cell (outside
   the out-of-scope ribbon group) should be better or unchanged.
4. The reference cells must not get worse: Imp 14181, Jungle horror 1042, Dagannoth Prime and Supreme,
   Spinolyp 5947, Apparition 4196–4198, Defiler 1724, and the chatheads.
5. Tune the constants by re-running the gallery over all 1,988 portraits, never by fixing one monster.
   Each tuning commit records the before and after on its checklist cells.

## Risks

- **Too many monsters fall back to whole.** A shape whose head band never reaches `HEAD_MIN` falls back.
  The gallery shows it, and the fix is to tune, not to special-case. Worth counting the fallbacks in the
  debug line during tuning.
- **Dragons' snouts are thin.** If the snout falls under `HEAD_MIN` × `H`, the head front lands behind
  it, on the neck. That's still much better than the wing, and it can be tuned.
- **The 128 grid is too coarse for a huge field of particles.** If a particle field is many times the
  body's size, the body covers few cells. Trimming helps, and so does the gallery count.
- **The transparency direction** is `[assumed]` until the first gallery run.

## Revision: hostile pass before the plan (2026-10-15)

A third-person read of sections 3 to 5, asking "a colleague wrote this; what breaks it?", found the
following. These points supersede the sections above where they differ.

- **G1: the head band ran into the wings.**
  - *Problem.* A winged demon's central run jumps to the full wingspan at the shoulders. A band "`W`
    rows tall" reaches those rows, so the head's median size becomes the wingspan, and the frame is the
    whole body. That is the very case this pass exists to fix.
  - *Fix.* The band grows one row (or column) at a time from the head top, and stops as soon as its
    length reaches the median run inside it. A head about as tall as it is wide stops near the neck,
    before the shoulders.
- **G2: the threshold was set by the wingspan.**
  - *Problem.* With `W` as the median, a demon's wing rows lift `W` toward the wingspan, and
    `0.2 × W` can be wider than its head, so the head top lands on the shoulders.
  - *Fix.* The typical run is the **lower quartile** of the non-empty runs (`TYPICAL = 0.25`), not the
    median. Horns and spikes are still far under it. `[assumed]` until the gallery shows it.
- **G3: the centre line followed the weapon.**
  - *Problem.* A big shield or club on one side pulls the *mean* column off the head.
  - *Fix.* The centre column is the **median** column of the filled cells.
- **G4: a crab-shaped kraken fell out of TOP.**
  - *Problem.* `FLAT = 0.3` drops the Spined kraken (height/width 0.304).
  - *Fix.* `FLAT = 0.32`.
  - *Measured* over `build/foe-gallery/index.tsv` with the new rule. TOP becomes: the spiders, krakens,
    scorpions (including Scorpia), rays (Manta, Stingray, Eagle, Butterfly), lobsters, Venenatis, Kalrag,
    the scarabs, the Loar Shadow and the Spawn, plus the Shadow, Suspicious water and Whirlpool. Those
    last three are flat or nearly flat; the height-0 ones get no portrait.
  - The scorpions, rays and lobsters were flagged by the operator as broken side views.
- **G5: the existing synthetic spider is height 0.**
  - *Problem.* The "no portrait at height 0" rule would turn it into null.
  - *Fix.* The test spider gets a 2-unit-thick body. Real flat models (the Shadow 4004, the
    Suspicious water 5946, the Me? 12235) are ground decals or hidden, and the operator flagged them as
    empty cells.
- **G6: fallbacks could pass silently.**
  - *Problem.* A frame that fell back to the whole body looks like a framing success at a glance.
  - *Fix.* The offline gallery renderer (plan Task 2) prints, per view, how many portraits found a
    head and how many fell back, so a tuning run is judged on a count as well as by eye.

### Corrections made while planning (2026-10-16)

Two of the points above were wrong, and working through the plan's test meshes showed it.

- **G2 is reversed: the typical run stays the median.**
  - *Why.* With the lower quartile, a forward spear or a long tail gives dozens of 1-cell columns and
    drags the quartile down to 1. The spear then counts as the head: the side-on spear test (Task 6)
    fails with it and passes with the median.
  - *What remains.* The winged-demon risk that G2 was about is watched by the offline count and the
    Task 5 wingtip test, not designed away.
- **G7: there is no "no head found" case.**
  - *Why.* The median column always holds a filled cell, and the median run always passes its own
    20% threshold, so a non-empty outline always yields a head. The spec's fallback, and its test row,
    are unreachable and are dropped.
  - *What it means.* The meaningful failure is a head-and-shoulders square larger than the whole body,
    which gets cut to it (*clamped*). G6's offline count reports clamped frames per view instead.
