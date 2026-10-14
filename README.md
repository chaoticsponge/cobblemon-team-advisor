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
- When `AUTO ON`, proactively switches whenever the best available bench Pokemon scores at least `+2` better, even if the active Pokemon has a positive score. Ties and marginal improvements keep the active Pokemon in, and trapped Pokemon do not attempt to switch.
- Uses a bundled Cobbleverse trainer snapshot to score known trainer moves by trainer name plus visible enemy species and level before falling back to species-level and level-up moves.
- Assumes worst-case known enemy level-up damaging moves until the opponent reveals more exact moves.
- Ignores TM, tutor, egg, and other inconsistent move sources for enemy assumptions.

Scores are a compact hint, not a simulator. They combine your best current offensive matchup with a much larger defensive weight for the opponent's likely offensive threat. They account for common ability immunities, Thick Fat, and ability bypasses such as Mold Breaker. Scores are not capped, so strong defensive matchups remain distinguishable.

## Build

Use Java 21 and Gradle 8.8 or newer, then run:

```sh
./gradlew build
```

The built mod is at `build/libs/cobblemon-team-advisor-1.7.0.jar`. Copy it into the profile's `mods` directory and restart the game.
