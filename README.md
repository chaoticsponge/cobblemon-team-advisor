# Cobblemon Team Advisor

Client-only NeoForge mod for Minecraft 1.21.1 and Cobblemon 1.7.3.

The main export use case is uploading your party and PC data to AI tools such as GPT or Claude for optimal Cobblemon team planning. When a PC screen opens, the mod writes synchronized party and box data to:

- `pokemon-export.json`
- `pokemon-export.csv`

The JSON file includes Cobblemon's serialized Pokemon data. The CSV file includes common sortable fields for quick filtering.

## Battle Helpers

- Shows matchup scores next to Pokemon levels on the battle switch screen.
- Labels moves with `KO` when the current move is estimated to knock out the selected target.
- Assumes worst-case known enemy level-up damaging moves until the opponent reveals more exact moves.
- Ignores TM, tutor, egg, and other inconsistent move sources for enemy assumptions.

Scores are a compact hint, not a simulator. They combine your best current offensive matchup with the opponent's likely offensive threat and simple ability counters.

## Build

Use Java 21 and Gradle 8.8 or newer, then run:

```sh
./gradlew build
```

The built mod is at `build/libs/cobblemon-team-advisor-1.0.0.jar`. Copy it into the profile's `mods` directory and restart the game.
