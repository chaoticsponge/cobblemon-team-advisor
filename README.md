# Cobblemon Team Advisor

Client-only NeoForge mod for Minecraft 1.21.1, checked against Cobblemon 1.7.3, 1.8.1 and 1.8.2.

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

The built mod is at `build/libs/cobblemon-team-advisor-1.19.0.jar`. Copy it into the profile's `mods` directory and restart the game.

The default compile dependency is the profile's Cobblemon 1.7.3 jar. To check another locally installed version, pass `-PcobblemonJar=/path/to/Cobblemon.jar` to Gradle.

Every release provides separate downloads labeled `cobblemon-1.7.3`, `cobblemon-1.8.1` and `cobblemon-1.8.2`. Choose the jar matching your Cobblemon version and install only one advisor jar. Existing releases remain available.

Build each release download against its matching dependency:

```sh
./gradlew build -PcobblemonVersion=1.7.3 -PcobblemonJar=/path/to/Cobblemon-neoforge-1.7.3+1.21.1.jar
./gradlew build -PcobblemonVersion=1.8.1 -PcobblemonJar=/path/to/Cobblemon-neoforge-1.8.1+1.21.1.jar
./gradlew build -PcobblemonVersion=1.8.2 -PcobblemonJar=/path/to/Cobblemon-neoforge-1.8.2+1.21.1.jar
```

Attach all resulting version-labeled jars to the same mod release. The original unlabeled v1.16.1 download remains available for existing links.

Version 1.16.1 fixes AUTO crashing in wild battles after updating to Cobblemon 1.8.1: that version renamed Pokédex progress `CAUGHT` to `OWNED`. Rare-encounter protection recognizes both names without referencing the removed enum field. Shiny, legendary and uncaught-species protection remains enabled.

`build` also checks defensive scoring, survival, move restrictions, trainer-roster loading and contradiction fallback, self-switch prevention and rejection retries, two-turn plans, counter preservation, observation learning, and field effects. Live gameplay must still be checked in Minecraft.

### Raid HP and KO advice

Raid Dens bosses are detected from their raid aspect, a matching world entity, or the Raid Dens dimension. The server does not sync their scaled shared HP total. During detected raids, the advisor suppresses KO labels and outgoing KO predictions in the survival planner, and ranks attacks by uncapped expected damage alongside survival and move effects. Shared raid damage does not train the ordinary single-battle damage estimator. It cannot promise a raid KO from a hidden HP pool.

In trainer and ordinary battles, the finishing preference uses minimum normal damage, including deterministic critical-hit moves but excluding random critical hits. It rewards moves that cover the remaining HP above moves needing a high roll, while retaining accuracy, turn order, status and recoil checks. `KO` means the estimated minimum damage suffices on a hit; `KO*` indicates the move can miss. The hint gives accuracy separately. Hidden stats and abilities can still make these estimates uncertain.

### Private Pokédex scan summaries

Completing a Pokédex scan prints a compact, colored summary in the scanning player's local chat. It shows the apparent species and form, combined-type weaknesses (×2 / ×4), resistances (×½ / ×¼), immunities, and offensive type strengths against single-type opponents. These are type-chart matchups; abilities, items and battle effects can change them.

The evolution guide lists the next branches and their level, item, trade, friendship, move, time, regional biome and other requirements. Four branches appear directly; additional branches are grouped into hover details. Registered Pokémon also get a summary after the scanner focuses, and repeated frames/registration packets do not repeat the same message. Close and reopen the scanner to scan the same target again.

Messages are added only to the local client's chat, with no chat packets or broadcasts. Each player who wants this feature installs the client mod. Evolution reference data comes from Cobblemon 1.7.3: multiplayer servers do not sync private evolution datapacks, so server rules may differ. Unknown species/forms are marked unavailable rather than assigned another form's evolution.

### Battle health accuracy

Opponent health values are already fractions even when Cobblemon supplies an absolute maximum HP stat. The advisor uses those fractions directly, divides only flat HP, and applies the same conversion to observed damage. It uses the synced maximum HP when available and the live battle aspects for regional-form stats, typing, moves and raid detection. This prevents false KO labels and priority-move finishing bonuses against healthy opponents.

### KO-first move selection

Trainer, wild and raid battles share one damage calculation and move-ranking policy. Estimated normal-hit KO probability takes precedence over two-turn planning benefits, then effective expected damage drives attack ranking, with smaller planning and battle-consequence adjustments. Current PP determines whether moves can be used or repeated; there is no reward for saving PP or an available opening move. Accuracy, turn order, recoil, immunities, charging and legal move restrictions still apply.

The calculator combines attack/defence stats, STAB, weaknesses/resistances, known abilities, items, field conditions and move-specific power. Added modifiers cover low-power/contact/punch/bite/pulse/slicing/sound/secondary-effect boosts, low-HP starter abilities, type-converting abilities, defensive damage reductions, Hustle, Compound Eyes, and variable-power moves including Acrobatics. Multi-hit KO estimates use hit-count probabilities, Skill Link and Loaded Dice. Random critical hits do not contribute to normal-hit KO odds. Feint and other protection-breaking moves can bypass Protect in the planner.

The planner caps damage to remaining HP before multiplying by hit probability, preventing inaccurate overkill from becoming a certain knockout. Detected raids with hidden shared HP use uncapped expected damage and survival advice without false KO claims. Hidden enemy stats, server-specific mechanics and unsupported special effects remain estimates; this is a client-side advisor, not the server's battle simulator.

### Preventing switch loops

Active and benched Pokémon use the same matchup score; benched Pokémon no longer get an extra planning bonus. Safe, accurate immediate KOs take precedence over voluntary switches even if an enemy could use a harmless priority move first. Small incoming hits do not justify switching for a type-score improvement alone.

After a confirmed automatic voluntary switch, the incoming Pokémon must use a move before another routine switch against the same opponent. Emergency switches remain possible if it risks fainting, or cannot damage the target and the replacement can. An opponent change allows a fresh counter-switch, and forced replacements and rejected choices do not create the lock. State resets with each battle and is tracked separately for each incoming Pokémon.

### Counter priorities and current HP

Replacement candidates must survive entry hazards, the expected incoming hit and harmful residual damage, and have an effective damaging move. Among viable candidates, defensive tiers are ordered: immunity, quarter resistance, resistance, neutral, weakness, then double weakness. Health and outgoing damage break ties within a tier; extra offensive power cannot reverse those defensive priorities.

The health contribution includes current HP, HP left after entry/incoming damage and remaining hit capacity. Switch hints show current and estimated post-hit HP. Safe immediate KOs and the switch-loop protection still take precedence over unnecessary voluntary swaps. Attacks continue to compare their fully modified damage rather than their type name: conditional moves such as Brine use their doubled power only when the target is at half HP or below, then apply stats, STAB, effectiveness and other known modifiers.

### Trainer win-rate CSV

`trainer-battle-stats.csv` in the Minecraft profile directory contains `mod_version,trainer_name,number_of_battles,win_rate`. Each version/trainer pair has its own row. A completed trainer battle counts when AUTO submitted at least one move or switch; mixed manual/AUTO battles count too. Manual-only battles, wild battles, raids represented as wild Pokémon, PvP, spectating, fleeing, disconnects and unrecognized outcomes are excluded. Results use the client's existing translated victory messages, with exact side-name matching; no extra server requests are made.

The CSV refreshes after each recognized win or loss. `trainer-battle-stats.json` stores exact integer wins and battle counts across restarts and upgrades; keep both files, and do not edit the CSV to change totals. Statistics begin with this release; earlier battles cannot be reconstructed. Comparisons reflect your local party, trainer mix and use of AUTO, not a controlled benchmark.

### Trainer overhead win estimates

Recognized nearby trainers display a second nameplate line such as `Est. win: ~72%` within 24 blocks, outside battle. Radical Cobblemon Trainers IDs already synchronized to the client select an exact bundled roster, including trainers who share names; other Cobblemon NPCs require a unique exact-name match. The bundled ID index covers 1,559 trainer definitions from RCT 0.18.1-beta. Unknown RCT teams show `team unknown`. No extra server requests are sent.

The preview runs 128 bounded singles trials against the full trainer team. It starts with your selected party member and uses current party HP, stats, abilities, items, status, moves and PP, with trainer levels, moves, IVs/EVs and known held items from the roster snapshot. It shares the advisor's damage calculations, including STAB, type interactions and common ability/item modifiers. Trials sample accuracy, ordinary damage rolls and speed ties, carry HP and PP across opponents, choose replacements, and allow emergency switches that spend a turn. Opener restrictions, consecutive-use cooldowns, choice locks, charging, recharge, healing, draining, recoil and existing damaging status are modeled.

This is an uncalibrated model estimate, separate from the measured win-rate CSV. It simplifies opponent decisions, HP-dependent damage into five HP bands, multi-hit counts, and switching. It does not simulate trainer bag items, random critical hits, setup/status strategies, all passive effects, weather/terrain changes, hazards, gimmicks, doubles or custom server changes. Percentages never claim a guaranteed 0% or 100%; unresolved trials produce a range or `uncertain`. A bundled team may differ from a server's configuration.

Preview results refresh after party changes and are cached. At most one new trainer is evaluated per half-second; simulations never run in the render callback. Labels use normal depth-tested rendering and respect the hidden HUD and invisible entities. Restart Minecraft after installing the updated jar.

### Productive switching and battle diagnostics

Voluntary switch candidates must survive entry and have a modeled useful follow-up action against the opponent's replies, accounting for speed, priority, status and charging. Switch forecasts use minimum normal outgoing damage and maximum normal incoming damage instead of average damage rolls. A Pokémon that survives the switch-in hit but faints before acting is excluded before immunity/resistance ranking. Forced replacements keep a legal fallback even when no safe counter remains.

After a confirmed voluntary switch, another switch against the same opponent still requires an action first. An emergency exception now requires a damaging replacement that survives the follow-up turn and provides a clear safety improvement, or safely escapes a completely ineffective matchup. Danger alone does not bypass the lock. Every voluntary switch must beat staying by a positive planning margin plus its switching/boost-loss cost. The defensive-score fallback that could accept a slightly worse plan is removed. The comparison normalizes starting HP so a fresh bench member does not create an artificial party-health gain; the planner already charges entry damage and the lost attacking turn.

Each battle now writes a local `battle-logs/<timestamp>-<battle-uuid>.jsonl` file. Rows include mod version, battle ID, time and turn, starting party HP/stats/moves/PP, battle messages, observed health changes, switch-candidate forecasts, switch decisions/reasons, AUTO move choices and alternatives, submitted switches, and invalid choices. Repeated identical evaluations within a turn are suppressed; changed forecasts can be recorded. Rows flush immediately for live diagnosis. Closing a battle does not itself imply a win or loss; explicit outcome messages and the existing trainer win-rate CSV remain the outcome sources.

Diagnostics only use already-received client data, record no ordinary chat, and make no server requests. Unavailable numeric estimates are JSON null. Old fights cannot be reconstructed retroactively. The new switching behavior and traces require a Minecraft restart; battle-model estimates still need live gameplay validation.

### Trainer label rendering fix

Trainer predictions render after the living entity, independently of the normal name-tag event, with Minecraft's nameplate orientation and attachment position. RCT trainers are identified by their registered `rctmod:trainer` entity type; their already-synced IDs are read from the loaded entity rather than an eagerly resolved optional-mod class. Computed labels are recorded at DEBUG level, and identity-read failures produce one warning per entity class. The range, local-only data use and prediction model are unchanged.

### Rare encounters, optimized loadouts and medicine

AUTO pauses before selecting an action against a shiny, legendary/mythical/Ultra Beast or uncaught wild species. It uses the existing client Pokédex record and visible battle data. Missing wild Pokémon details defer automation until they arrive. A brief action-bar notice identifies the reason; explicitly switching AUTO back on approves the paused encounter. Newly encountered wild Pokémon are checked separately. Trainer-owned Pokémon are unaffected, and detected raid bosses remain eligible for AUTO because their capture phase requires defeating them first.

Opening a PC adds a `Loadouts` button. `Create loadout` generates a general-purpose balanced team from synchronized party/PC Pokémon under the editable level cap. Repeated creates prefer alternative teams when available. The search combines current moves, normal full-health offensive estimates, STAB/common ability and item modifiers, offensive coverage, defensive weaknesses/resistances/immunities, speed, bulk and physical/special balance. It favors distinct species and fills additional slots from owned duplicates only if necessary. This is a bounded heuristic, not an exhaustive team solver; variable moves and conditional effects remain approximate, and it does not learn or change moves.

Drafts do not change the party. `Save` stores a named local loadout, and `Apply` assembles the selected draft or saved team through normal Cobblemon PC move/swap packets. Each move waits for the synchronized result before the next move; missing Pokémon, rejected/unconfirmed swaps or full boxes stop application. Leaving the screen stops further moves, although an already-submitted move may finish. A collection with fewer eligible Pokémon produces a smaller team. Saved loadouts live under `optimized-loadouts/<pc-uuid>.json`, separately from Party Extras' server presets. Corrupt saves are preserved, and no existing preset is overwritten.

With AUTO enabled, the advisor can use Cobblemon potions and persistent-status cures from the main inventory or hotbar on the active Pokémon. It compares the estimated item turn plus follow-up against attacks and useful switches, keeps reliable immediate KOs, and requires the treated Pokémon to survive the modeled sequence with a clear improvement. Medicine consumes a turn. Revives, stat boosters, food/berry healing and arbitrary modded medicines are not automated. Item amounts use the locally available mechanics, so server-specific changes can affect estimates.

Item use goes through ordinary held-item interaction and the server's own enabled-target selection callback; server inventory, target and battle restrictions still apply. Inventory medicine is temporarily swapped into the selected hotbar slot, then restored if the expected inventory state remains. Denied or unconfirmed use stops further automatic item attempts for that battle, and the same decision request is not retried. Requests, target selections and results appear in battle traces. Calculations and saved drafts are local; applying teams and using items necessarily send normal gameplay requests.

### Unfocused AUTO battles

When Dynamic FPS is installed, the advisor temporarily raises its frame target to at least 30 FPS while AUTO is enabled in a battle. The installed Dynamic FPS version uses that target to skip low-rate rendering; restoring normal battle rendering also keeps GUI/decision processing responsive while unfocused. No Dynamic FPS settings file is changed. Its usual throttling resumes when AUTO stops or the battle ends. OS suspension, network/server lag and other throttling mods are outside this compatibility fix. Build/regression checks pass; the new screen, native medicine interaction, PC application and background behavior still require live gameplay validation after restarting.

### Automatic battle mechanics (v1.17.0)

AUTO compares ordinary moves with Mega Evolution, Ultra Burst, Z-Moves, Dynamax/Gigantamax and Terastallization offered by the current server request and battle UI. Required equipment, held items, once-per-battle limits and server restrictions still apply. It submits one native move response containing the selected base move, target and mechanic; it does not transform Pokémon locally or send commands.

The scorer uses converted Z/Max power (including per-move overrides and fixed-power signature moves), move category, accuracy and type effectiveness. Mega/Ultra candidates use known form stats, typing and ability. Tera candidates use their new defensive type while retaining original-type STAB; confirmed Tera messages update subsequent turns for both sides. Confirmed Stellar uses are tracked per move type; the two-turn forecast does not consume those boosts internally. Dynamax survival estimates use the conservative level-zero HP boost only on activation. Follow-up plans do not reuse Z-Moves. Switch and medicine decisions also consider offered battle mechanics.

Rejected transformations are suppressed for the rest of that battle, leaving ordinary moves and other mechanics available. During an already active Dynamax, only the rejected base move is suppressed so other Max Moves remain usable. The battle trace records mechanic submissions. No extra chat messages or settings are added.

These remain bounded estimates: unusual form-changing items, entry ability effects, Z-status bonuses, Max weather/terrain/stat effects, custom server moves and the exact remaining Dynamax duration are not fully simulated. Live in-game validation is still required; build checks do not prove server acceptance. The bundled powered-move table is exported from the installed Showdown data with `scripts/export-gimmick-moves.cjs`.

### Immediate Mega activation (v1.17.1)

When AUTO has a legal server-offered Mega move, it activates the native Mega button and clicks the best legal Mega move in the same decision. The move response includes both the attack and Mega Evolution; toggling the button does not consume a separate turn. Mega has priority over ordinary attacks, other battle mechanics, healing items and voluntary switches, even if the untransformed attack scores higher. Forced replacements, rare-encounter pauses, move legality and rejected-mechanic protection still apply. The server controls eligibility and battle order; the client cannot evolve before its first legal action or guarantee survival.

### Complete battle reviews (v1.18.0)

All client-observed battles are recorded, with AUTO on or off: trainers, wild encounters, PvP and spectating. Trainer totals now have two separate tables: `trainer-all-battle-stats.csv` includes completed manual and AUTO-assisted trainer fights; the existing `trainer-battle-stats.csv` keeps its AUTO-only counting rules and history. Both are grouped by mod version and trainer name. No earlier history is invented.

Every battle has a flushed `battle-logs/<timestamp>-<uuid>.jsonl` trace, a matching `.summary.json` report, and one row in `battle-summary.csv`. The index records the battle at start, then updates its outcome and manual/AUTO-assisted label. Summaries refresh at turn boundaries, AUTO submissions, results and battle closure, so interrupted fights retain an unresolved record. JSONL rows carry sequence numbers for linking review evidence.

Team history retains synchronized friendly party stats, items, abilities, moves and PP, encountered enemy species/forms, visible HP/status/boosts, observed moves, ability-related messages and stable translation IDs. Enemy species are explicitly apparent species: Illusion may hide the actual Pokémon. Matched bundled trainer rosters are marked assumed and kept separate from observed data; unrevealed opponents, moves and abilities remain unknown. Ambiguous move users are logged with candidate IDs rather than guessed. Weather/terrain and selected mod versions are included.

Each report includes the explicit result message and review events for faints, missed/failed/prevented actions, immunities, critical hits, invalid choices, repeated AUTO switches without attacking, damage taken on a switch turn, and predicted KOs not observed during that move's health updates. Actual moves are tracked for manual and automatic play, and AUTO decisions include legal alternatives and mechanic IDs. Damage sources and reasons for victory/defeat are observations with chronological context, not claimed proof that a different choice would have won. Disconnections, fleeing or missing result messages never become invented losses.

Reports remain local and use existing client data only. They do not automatically retrain or modify the battle policy. Use them to compare versions, reproduce failures and choose the next scoring fixes. Restart Minecraft to enable the new logging. Older JSONL files remain readable, but missing opponent data cannot be recovered retroactively.

### Owned Pokémon attribution and readable exports (v1.18.1)

Battle messages identify Pokémon with structured owner/name components (for example, “Brock’s Gigalith”), while live Pokémon display names contain only “Gigalith”. The matcher now uses both components to attach moves, ability events, faints and effects to the correct UUID. This handles duplicate species in PvP and localized ownership wording. A genuinely ambiguous owner/name remains unresolved. Generic ability messages also preserve literal ability display names and recognized ability IDs.

Open `battle-pokemon.csv` in the profile directory for one row per encountered Pokémon, including owner, side, species, level, synchronized known moves, separately labeled assumed trainer moves, actual moves used, ability events, item and last observed HP. The JSON review now groups `teams.ally`, `teams.enemy` and `teams.unknown` as well as keeping the original flat `pokemon` list. Win-rate CSV files remain aggregate totals; they are not move logs. An empty `moves_used` means no move was attributed to that Pokémon, including enemies defeated before acting. Unknown moves are never filled from assumptions.

The explicit maintenance command below rebuilds closed v1.18.0 summaries where ownership can be recovered unambiguously from recorded `owned_pokemon` messages. It preserves source versions and original JSONL traces, backs up existing summaries as `*.summary.before-1.18.1.json`, and refreshes the per-Pokémon CSV. It does not change win-rate totals or run automatically during gameplay. Missing or ambiguous information stays unknown.

```sh
./gradlew reviewBattleLogs -PbattleLogDirectory="/path/to/profile/battle-logs" -PcobblemonJar="/path/to/Cobblemon.jar"
```

### Survival-aware tactics (v1.19.0)

Reviewing the Clair and Dragon Tamer Ramiro logs exposed status/attack scoring on different risk scales: attacks were penalized for a forecast faint while unsafe setup could still rank higher. Status and attack plans now share that survival penalty. Stat setup must provide a useful boost, survive the modeled continuation and execute a damaging follow-up; otherwise it becomes a last-resort legal fallback. Lower current HP reduces setup preference further. Protect and healing are exempt from the setup HP penalty and remain available when their modeled survival value warrants them. Reliable KO ranking is retained.

The planner now distinguishes performing the first setup from actually acting on the next turn. Attack, Special Attack, Defense and Special Defense changes are modeled separately, including self-inflicted drops: Iron Defense no longer falsely mitigates Thunderbolt, and Nasty Plot no longer boosts physical attacks. These remain bounded estimates rather than a complete simulator of every move interaction.

Explicit recharge messages schedule one known non-attacking turn. Advice and the two-turn planner can use that window to heal or attack, then resume modeling enemy attacks on the following turn. A missed or blocked beam does not invent a free recharge. Recharge state expires and resets on switching. Consecutive Destiny Bond attempts are suppressed, addressing the repeated failed attempt seen in the Ramiro fight.

Regressions cover low-HP setup followed by a faint, surviving setup but dying before the follow-up, useful speed/attack setup, low-HP Protect with residual damage, physical/special boosts and defense drops, recharge healing opportunities and recharge expiration. These changes target observed wasted turns; higher live win rates require new battles and are not established by replaying the old outcomes.
