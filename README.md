# Cobblemon Team Advisor

Client-only NeoForge mod for Minecraft 1.21.1 and Cobblemon 1.7.3.

The main export use case is uploading your party and PC data to AI tools such as GPT or Claude for optimal Cobblemon team planning. When a PC screen opens, the mod writes synchronized party and box data to:

- `pokemon-export.json`
- `pokemon-export.csv`

The JSON file includes Cobblemon's serialized Pokemon data. The CSV file includes common sortable fields for quick filtering.

## Battle Helpers

- Shows matchup scores next to Pokemon levels on the battle switch screen.
- Highlights the currently recommended switch or move.
- Labels moves with `KO` when the current move is estimated to knock out the selected target.
- Shows a battle-only `AUTO OFF` / `AUTO ON` toggle in the top-right corner. It defaults off.
- When `AUTO ON`, automatically clicks `FIGHT` or `SWITCH` from the root battle menu, then selects the highest-scored move or best available switch.
- If no move has a positive score and no switch is useful, `AUTO ON` still selects a legal fallback move so the battle continues.
- Prioritizes immunity and resistance to the opponent's worst assumed damaging move over offensive type advantage. Ability immunities apply per attack, so coverage moves can still make a matchup unsafe.
- Scores live HP, actual friendly defensive stats, visible stat boosts, status, and estimated incoming damage. A switch must survive estimated entry damage, the enemy's strongest hit, and modeled status damage.
- When `AUTO ON`, proactively switches to a meaningfully better surviving option. Switching costs a turn and can lose boosts; ties and marginal improvements keep the active Pokemon in, and trapped Pokemon do not attempt to switch.
- Stays in for an estimated reliable KO before the enemy acts. Compares speed, move priority, paralysis, visible boosts, Choice Scarf, Tailwind, and Trick Room; speed ties do not count as acting first.
- Uses a bundled Cobbleverse trainer snapshot matched by trainer name plus enemy species and level. An unambiguous trainer set or four revealed moves prevents speculative coverage from being added, including when the set contains status moves. Conflicting reveals and merged trainer variants fall back to conservative assumptions.
- Tracks entry hazards, their removal and Court Change through translation keys in client battle messages. Scores Stealth Rock, Spikes, Toxic Spikes and Sticky Web, including airborne Pokemon, Heavy-Duty Boots and Magic Guard.
- Scores attack accuracy, common recoil and draining effects, fixed-damage attacks, healing, common status moves, safe setup, and hazard utility. Avoids full-health healing and capped or unsafe setup.
- Tracks field entries and battle turns to avoid repeated First Impression, Fake Out and Mat Block failures. Also respects consecutive-use restrictions for Gigaton Hammer / Blood Moon, observed Last Resort prerequisites, and repeated No Retreat. Actual switch-in packets reset field-entry restrictions, including after manual switches; rejected button choices do not consume move history.
- Shows a short recommendation explaining the chosen move or switch and whether enemy moves are known or assumed.
- Assumes worst-case damaging coverage from species-level and level-up moves when the enemy set is uncertain.
- Ignores TM, tutor, egg, and other inconsistent move sources for enemy assumptions.

Scores are estimates, not a full battle simulator. Enemy stats use synchronized values where available and estimates otherwise. Damage rolls, common move effects and abilities are modeled; weather, terrain, many volatile effects, and some unusual moves are not. Unsupported utility moves remain legal fallbacks. Multi-Pokemon battles still use the selected or first adjacent opponent rather than planning a whole turn across all opponents.

The trainer snapshot contains some merged variants with more than four moves; these are labeled `assumed moves`, not treated as exact sets. Bundled move-effect metadata is extracted from the profile's Cobblemon Showdown data. Hazard state resets between battles and is learned from messages received during the current battle.

## Build

Use Java 21 and Gradle 8.8 or newer, then run:

```sh
./gradlew build
```

The built mod is at `build/libs/cobblemon-team-advisor-1.8.1.jar`. Copy it into the profile's `mods` directory and restart the game.

`build` also runs regression checks for defensive scoring, survival, speed and priority, accuracy and recoil, healing, trainer-set precedence, and field conditions. Live gameplay must still be checked in Minecraft.
