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
- Rejects self-switches using UUIDs from the live battlefield and current server request, excludes destinations reserved by another slot, and suppresses a rejected destination for the same request. AUTO only submits the current unanswered request.
- Compares bounded two-turn sequences: attacking twice, healing or setting up before attacking, and switching while taking an enemy hit before attacking. Includes common protection, Substitute, charging, recharge, recoil, draining, and Rest sleep.
- When `AUTO ON`, proactively switches to a meaningfully better surviving option. Switching costs a turn and can lose boosts; ties and marginal improvements keep the active Pokemon in, and trapped Pokemon do not attempt to switch.
- Stays in for an estimated reliable KO before the enemy acts. Compares speed, move priority, paralysis, visible boosts, Choice Scarf, Tailwind, and Trick Room; speed ties do not count as acting first.
- Uses full trainer rosters from Radical Cobblemon Trainers 0.18.1-beta when trainer name, observed species/levels and revealed moves uniquely match. Bundled stats, natures, abilities and unambiguous held items improve estimates; revealed data overrides assumptions.
- Preserves unique defensive and offensive counters for a matched trainer's remaining team, accounting for observed faints and other healthy party alternatives. Ambiguous or contradicted rosters do not drive preservation.
- Uses a bundled Cobbleverse trainer snapshot matched by trainer name plus enemy species and level. An unambiguous trainer set or four revealed moves prevents speculative coverage from being added, including when the set contains status moves. Conflicting reveals and merged trainer variants fall back to conservative assumptions.
- Tracks entry hazards, their removal and Court Change through translation keys in client battle messages. Scores Stealth Rock, Spikes, Toxic Spikes and Sticky Web, including airborne Pokemon, Heavy-Duty Boots and Magic Guard.
- Scores attack accuracy, common recoil and draining effects, fixed-damage attacks, healing, common status moves, safe setup, and hazard utility. Avoids full-health healing and capped or unsafe setup.
- Tracks field entries and battle turns to avoid repeated First Impression, Fake Out and Mat Block failures. Also respects consecutive-use restrictions for Gigaton Hammer / Blood Moon, observed Last Resort prerequisites, and repeated No Retreat. Actual switch-in packets reset field-entry restrictions, including after manual switches; rejected button choices do not consume move history.
- Tracks Taunt, Encore, Disable, choice-item move locks, Protect and Substitute. Field-entry changes clear the appropriate restrictions.
- Models common weather and terrain effects on damage, speed, recovery, residual damage and status immunity, plus Reflect, Light Screen and Aurora Veil. Guaranteed critical-hit moves bypass screens as appropriate.
- Learns damage corrections from clean, nonlethal single hits. Critical hits, residual damage, substitutes, multi-hit attacks and censored KO damage are excluded. Corrections are specific to the Pokemon, move and battle conditions.
- Refines equal-priority turn-order estimates after repeated clean observations in singles. Priority, known speed changes and random-order effects are accounted for; conflicting observations remove the learned ordering.
- Shows a short recommendation explaining the chosen move or switch and whether enemy moves are known or assumed.
- Assumes worst-case damaging coverage from species-level and level-up moves when the enemy set is uncertain.
- Ignores TM, tutor, egg, and other inconsistent move sources for enemy assumptions.

Scores are estimates, not a full battle simulator. The two-turn search considers up to eight opponent replies and uses approximate damage and common effects. It does not simulate every volatile effect, possible opponent switch, hidden item, multi-Pokemon interaction, or unusual move mechanic. Damage rolls are estimated, and setup effects in the search are simplified. Multi-Pokemon battles still focus on the selected or first adjacent opponent; turn-order learning is limited to singles.

Trainer rosters are bundled snapshots, not live server data. Matching is conservative: ambiguous teams, revealed contradictions and alternative held items stay uncertain. The older move snapshot remains a fallback; merged sets with more than four moves are labeled `assumed moves`. Bundled move metadata comes from the profile's Cobblemon Showdown data. Observed moves, field conditions and learned estimates reset between battles. Move-message attribution requires a unique matching active name; ambiguous names are not guessed. No live-game win-rate comparison has been performed.

## Build

Use Java 21 and Gradle 8.8 or newer, then run:

```sh
./gradlew build
```

The built mod is at `build/libs/cobblemon-team-advisor-1.11.0.jar`. Copy it into the profile's `mods` directory and restart the game.

`build` also checks defensive scoring, survival, move restrictions, trainer-roster loading and contradiction fallback, self-switch prevention and rejection retries, two-turn plans, counter preservation, observation learning, and field effects. Live gameplay must still be checked in Minecraft.

### Raid HP and KO advice

Raid Dens bosses are detected from their raid aspect, a matching world entity, or the Raid Dens dimension. The server does not sync their scaled shared HP total. During detected raids, the advisor suppresses KO labels and outgoing KO predictions in the survival planner, and ranks attacks by uncapped expected damage alongside survival and move effects. Shared raid damage does not train the ordinary single-battle damage estimator. It cannot promise a raid KO from a hidden HP pool.

In trainer and ordinary battles, the finishing preference uses minimum normal damage, including deterministic critical-hit moves but excluding random critical hits. It rewards moves that cover the remaining HP above moves needing a high roll, while retaining accuracy, turn order, status and recoil checks. `KO` means the estimated minimum damage suffices on a hit; `KO*` indicates the move can miss. The hint gives accuracy separately. Hidden stats and abilities can still make these estimates uncertain.

### Private Pokédex scan summaries

Completing a Pokédex scan prints a compact, colored summary in the scanning player's local chat. It shows the apparent species and form, combined-type weaknesses (×2 / ×4), resistances (×½ / ×¼), immunities, and offensive type strengths against single-type opponents. These are type-chart matchups; abilities, items and battle effects can change them.

The evolution guide lists the next branches and their level, item, trade, friendship, move, time, regional biome and other requirements. Four branches appear directly; additional branches are grouped into hover details. Registered Pokémon also get a summary after the scanner focuses, and repeated frames/registration packets do not repeat the same message. Close and reopen the scanner to scan the same target again.

Messages are added only to the local client's chat, with no chat packets or broadcasts. Each player who wants this feature installs the client mod. Evolution reference data comes from Cobblemon 1.7.3: multiplayer servers do not sync private evolution datapacks, so the message notes that server rules may differ. Unknown species/forms are marked unavailable rather than assigned another form's evolution.
