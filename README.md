# Foe

The monster you're fighting, at a glance: its HP, its combat levels, and what element it's weak to.

[![License](https://img.shields.io/badge/license-BSD--2--Clause-blue.svg)](LICENSE)
[![CI](https://github.com/fevra-dev/Foe/actions/workflows/ci.yml/badge.svg)](https://github.com/fevra-dev/Foe/actions/workflows/ci.yml)

Foe is a RuneLite plugin that shows one small panel for your current target. It works offline:
nothing is fetched while you play. It never invents a value: before your first hit you see only the
monster's max HP, and a weakness appears only when the game or the bundled table says what it is.

**Foe overlaps RuneLite's built-in Opponent Info overlay for monsters.** If you run both, you'll see
a monster's HP twice. Opponent Info also covers player opponents, which Foe doesn't, so keep it on if
you fight other players.

## What it shows

- **HP.** Exact when Foe has watched every hit since the monster was at full health. Otherwise it's
  the middle of the range the health bar allows. The bar never claims more precision than it has.
- **Combat levels.** The combat level beside the name, plus Attack, Strength, Defence, Ranged and
  Magic in Full detail. Levels of 1 or less are left out.
- **Elemental weakness.** Taken from a table bundled with the plugin, for example `Fire 50%`. When
  one of your spells lands, the game reports the weakness, and Foe records it. A reported element
  wins over a different element in the table. A monster with no weakness shows nothing.
- **Portrait** (off by default). A small picture of the monster's head, drawn from its own models.

The panel follows the monster you attack. In multi-combat it stays on your target, and it clears when
the monster dies.

## Settings

| Setting | Default | Choices |
|---|---|---|
| Layout | Stacked | Stacked, One line |
| HP text | Current/max | Current/max, Current, Percent, None |
| HP text position | Beside bar | Beside bar, Inside bar |
| Detail | Full | Full, Compact |
| Stale HP style | Faded | Faded, Hollow |
| Show weakness | On | |
| Show weakness % | On | |
| Linger after combat | 10 s | 0 to 60 s |
| Background opacity | 61% | 0 to 100% |
| Show portrait | Off | |

## Credits

Foe's elemental weakness table (`src/main/resources/com/foe/weakness-table.tsv`) is derived from the
[Old School RuneScape Wiki](https://oldschool.runescape.wiki), whose content is licensed under
[CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/). The table carries its own notice,
[`weakness-table.LICENSE`](src/main/resources/com/foe/weakness-table.LICENSE), and the wiki's editors are credited
through each page's history. The plugin's code is licensed separately (see below).

## Security

To report a vulnerability, see [SECURITY.md](SECURITY.md).

## License

The plugin code: [BSD 2-Clause](LICENSE). The weakness table: CC BY-NC-SA 3.0, as above.
