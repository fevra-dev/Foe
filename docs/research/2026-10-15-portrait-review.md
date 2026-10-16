# Portrait review, 2026-10-15

The operator went through the `::foegallery` sheets (1,988 portraits on 22 sheets) and saved about 210
screenshots of bad cells to `~/Desktop/FIX - FOE/` (209 files, `Screenshot 2026-10-15 at *.png`). Each
cell shows the name, the id and the view with its extents (`VIEW wW hH lL`), so a screenshot is its own
test case. This note groups those cells by cause so the fix can be designed once per cause rather
than once per monster.

## What the operator asked for

- **Dragons:** almost every one shows only a wing. Fix them.
- **Winged demons:** too much empty space above the head. Centre the face in the frame and draw it closer.
- **The default for a creature: its face, centred and close.** Some top-down creatures (the cockatrice,
  for example) would look better from the face than from above.
- **Black cells, empty cells, and framing too close or too far** across the whole set.
- **Question:** could a portrait be turned around 360° with the mouse?

## Where the renderer stands (read from the code, 2026-10-15)

- **Source.** The models are the game's own, from the cache. `PortraitModels.load` calls
  `client.loadModelData` on the chathead models, or on the body models when there's no chathead. It
  then merges, recolours and lights them, all with the game's own calls. No image ships with the
  plugin.
- **Drawing.** Foe draws the result with its own small renderer (`Portrait.render`): flat-shaded
  triangles sorted far to near. The game's renderer isn't used.
- **What that renderer skips, compared with the game:**
  - **Transparency.** Every face is drawn opaque.
  - **Textures.** A textured face is drawn grey at its light level.
  - **Animation.** The model is drawn in its stored pose, never the idle pose.
- **Framing.** Framing comes from the bounding box of all drawn faces. UPRIGHT crops the top 35% of
  the height. SIDE crops the front 30% of the length, starting at the top of that slab.

### The API in this RuneLite version

Checked with `javap` against `runelite-api-1.13.1.jar`:

- **Per-face transparency is available.** `getFaceTransparencies()` is on the shared `Mesh`
  interface, which both `Model` and `ModelData` extend (`javap net.runelite.api.Mesh`). A first pass that
  grepped only `Model` and `ModelData` missed it and wrongly said there was none. `Model` also has
  `getFaceRenderPriorities()` and the vertex normals.
- **The pose looks possible, but nothing has run it.** `Client.loadAnimation(int)` and
  `Client.applyTransformations(Model, Animation, int, Animation, int)` exist.
  - `NPCComposition` has no idle-animation getter. Only a live `Actor` has `getIdlePoseAnimation()`.
  - So in combat (where we have the NPC) the idle pose is reachable, but in the gallery it isn't.
- **The game can render an NPC itself.** `WidgetModelType` has `NPC_CHATHEAD` and `MODEL`, and a widget
  renders with textures, transparency and animation. But that draws into the game's interface, not into
  Foe's Java2D overlay.

## Causes, with the cells that show them

The causes below are hypotheses read off the pictures. Each one needs a probe to confirm it: a dump of
the faces of a few ids from each group.

| # | Cause | What it looks like | Cells (id) |
|---|---|---|---|
| 1 | **Effect, shadow or aura faces drawn opaque** (the renderer never reads `getFaceTransparencies()`) | Black or solid slabs over or under the monster | Blood Moon 13011, Blue Moon 13013, Eclipse Moon 13012, Enraged Blood Moon 13485, Great Olm (Right claw) 7553, Bee Swarm 13658, Vardorvis 12223, Sol Heredit 15554, Branda 12596/14148, Porazdir 7515 (red), Serpent shaman 12811, Jaguar warrior 12810, Fremennik warband 12814/12815/12816 (grey-green slab), Monk of Zamorak 16271/16273 (black silhouette) |
| 2 | **Stray geometry stretches the box:** particles, sparks, a projectile ring | A field of dots, the creature small or off-frame | Zalcano 9050, Hespori 8583/11192/15615, Eldric 14147/14149, Fire elemental 1365/14150, Tz-Kih 2189, Scarab Swarm 11723, Barrelchest 600, Spiritual mage/warrior/ranger 2242/2243/2244 (red star) |
| 3 | **A weapon or horn sets the top or the front**, so the crop frames the appendage | Only a spear, staff, horns or antlers | Tribesman 530, Tribeswoman 11955, Khazard trooper 4970, Goblin 2247/5204, Goblin Champion 3353, Hobgoblin Champion 3354, Aviansie 3170–3182, Flockleader Geerin 3164, Spiritual ranger 3167, Wingman Skree 3163, Zombie pirate 13489, Justiciar Zachariah 5977, Pyrefiend 433, Pyrelord 6762, Flaming pyrelord 7394, Revenant pyrefiend 7932, Icefiend 3140, Waterfiend 2916, Sunlight antelope 13133, Moonlight antelope 13132, Oryx 13006, Stag 1845, Impaler deer 15759, Custodian stalker 14702/14703, Commander Zilyana 2205, Penance Ranger 5757, Giant wasp 15438, Chaos Elemental 2054 |
| 4 | **Dragons (SIDE): the crop lands on the wing.** The front slab's top is a wing, and the head may sit lower or further forward | A wing membrane, cut off at the right edge | every chromatic and metal dragon: Red 247–251, Black 253–255/14146, Baby blue 241–243, Baby red 137, Bronze 270, Iron 272, Steel 139/274, Mithril 2919, Rune 8027/8031, Lava 6593/12607, Frost 14922, Crystalline 9033, Corrupted 9047, Revenant 7940, Brutal 7274/7275, Elvarg 817, King Black Dragon 239 |
| 5 | **Winged demons (UPRIGHT): the wingtips set the top**, so the face sits low with empty space above | A face in the lower half | Black demon 240/1432/2049–2052, Greater demon 2026–2029, Demon 142, Jungle Demon 1443, Holthion 8996, Doomion 8995, Kolodion 1609, Balfrug 3132, Tstanon 3130, Reanimated demon spawn 7287, Revenant demon 7936 |
| 6 | **Unposed or textured bosses** draw as black ribbons or lattices. It could be the stored pose or textures drawn wrong; a probe will tell which | Black or coloured diagonal lattices | Nylocas (all) 8342–8384/16234/16235, The Nightmare and Phosani's 377/9416–9430, The Maiden 8360–8365/15986–15992, Wyrm 8611, Wyrmling 13032/16296, Shadow Wyrm 10399, Drake 8613, Guardian Drake 10401, Alchemical Hydra 8620/8621, Araxxor 13668/13669, Venenatis 6610, Xarpus 8341, Zulrah 2042–2044, Galvek 8094 |
| 7 | **A long body seen from the side is a thin line** (a snake, a ray), or **SIDE picked the wrong end** | A sliver at the top, or a tail | Snake 1037/13002, Big Snake 2978, Sea Snake Young 1097, Swamp snake 5615, Narwhal 15202, Manta ray 15220, Stingray 15218, Eagle ray 15214, Scorpion 2479/13001, Scorpia 6615, Crypt rat 1679, Loar Shadow 1276, Tortoise 6075/6076, Mutated/Warped Tortoise 12465/12490, Penance Queen 5775, Colossal Hydra 10402, Hueycoatl tail 14014 |
| 8 | **Nothing drawn**, though the extents are not zero | An empty cell | Sanguidae 15756/16225, Crypt spider 1683, Giant bat 4504, Experiment No.2 5126, Venenatis' Spiderling 12000 |
| 9 | **Flat models** (height 0): there's no portrait to make, so show none | Empty | Shadow 4004, Suspicious water 5946, Me? 12235 |
| 10 | **Too close:** UPRIGHT crops a big or wide body to a patch | A texture-like close-up | Strange Creature 12073–12081, Stone Guardian 8064–8066, Golem 9051, Swamp Crab 8297/8298, Tok-Xil 141/2193, Slagilith 1362, Blood spawn 8367, Ent trunk 12112, Mystery fruit 15161, Leech 3233, Mammoth 6604 |
| 11 | **TOP where a face would be better** (operator: cockatrice and similar) | From above | to list from TOP-01: birds, wyverns, frogs (addendum 20 put frogs in TOP; the operator liked them from the side) |

### Fine, keep as reference

These show a face centred and close, which is the target for every other cell:

- Imp 14181, Jungle horror 1042, Defiler 1724, Abyssal walker 2586, Bat/Albino bat/Death wing,
  Experiment 1274.
- Spinolyp 5947, Dagannoth Prime and Supreme, Spiritual mage 2212/3161, Apparition 4196–4198, Karamel
  4882.
- Every chathead except Lizardman 10948, which is tiny, and Sneaky undead fowl 4419, which is a stick.

## Direction (to brainstorm, not decided)

- **Find the head instead of trusting the box.**
  - Drop faces that can't be the body before framing: disconnected small pieces (connected components
    by shared vertices, keeping the large ones), and likely effect faces.
  - Then frame on mass, not extents: the area-weighted silhouette. For UPRIGHT, the head is the first
    band from the top whose width is a real share of the body's; horns, spears and wingtips are thin.
  - Centre the crop on the face, and size it to the head, not to a fixed share of the height.
- **Measure, then decide.** Every row in `index.tsv` plus these screenshots is a labelled corpus. A
  new framing rule should be judged by re-running `::foegallery` over the whole corpus, not on one
  monster at a time (the frog, spider and demon regressions of addenda 20–22 came from fixing one
  monster at a time).
- **The idle pose** may fix cause 6. It's reachable in combat only, via the live NPC's
  `getIdlePoseAnimation()` and `applyTransformations`. That makes the gallery and the panel draw
  different poses unless the gallery gets the animation id another way `[assumed — no probe run]`.
- **Turning the portrait 360°.** The renderer is Foe's own, so a turn is only a different projection,
  and cheap. It doesn't belong in the combat panel: it would mean taking mouse drags over the game,
  for a glance-only display. It fits the v1.1 monster search panel. As a dev tool, the gallery could
  draw each family at 8 angles, so a fixed best angle can be picked per shape.
