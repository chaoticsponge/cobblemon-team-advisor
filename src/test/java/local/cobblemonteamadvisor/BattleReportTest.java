package local.cobblemonteamadvisor;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BattleReportTest {
    static void run() {
        Path root = null;
        try {
            root = Files.createTempDirectory("battle-report-test");
            BattleTrace.initialize(root, "new-version");
            UUID id = UUID.randomUUID();
            BattleTrace.sync(id, Map.of("allies", "Player", "enemies", "Bertha", "kind", "trainer"));
            check(Files.readAllLines(root.resolve("battle-summary.csv")).size() == 2, "Even an interrupted first turn must have a battle index entry");
            var snapshot = Map.of("pokemon", List.of(Map.of("pokemon", "own", "species", "Gengar", "side", "ally"),
                    Map.of("pokemon", "bench", "species", "Golurk", "side", "ally"),
                    Map.of("pokemon", "foe", "apparent_species", "Hippowdon", "side", "enemy")),
                    "assumed_rosters", Map.of("trainer", List.of("assumed extra Pokemon")));
            BattleTrace.snapshot(snapshot); BattleTrace.snapshot(snapshot);
            BattleTrace.event("move_used", Map.of("pokemon", "", "move", "tackle", "source_text", "ambiguous name"));
            BattleTrace.event("health", Map.of("pokemon", "foe", "before_fraction", 1, "after_fraction", Double.NaN));
            BattleTrace.turn(1);
            BattleTrace.event("auto_switch_submitted", Map.of("pokemon", "bench", "forced", false));
            BattleTrace.event("move_used", Map.of("pokemon", "foe", "move", "earthquake"));
            BattleTrace.event("health", Map.of("pokemon", "bench", "before_fraction", 1, "after_fraction", 0.5));
            BattleTrace.turn(2);
            BattleTrace.event("auto_switch_submitted", Map.of("pokemon", "own", "forced", false));
            BattleTrace.event("ability_event", Map.of("key", "cobblemon.battle.ability.generic", "pokemon_refs", Map.of("0", "foe"),
                    "translation_keys", List.of("cobblemon.ability.sandstream")));
            BattleTrace.event("auto_move_submitted", Map.of("pokemon", "own", "opponent", "foe", "move", "shadowball", "advice", Map.of("ko", true)));
            BattleTrace.event("move_used", Map.of("pokemon", "foe", "move", "earthquake")); // Faster foe must not discard our pending prediction.
            BattleTrace.event("move_used", Map.of("pokemon", "own", "move", "shadowball"));
            BattleTrace.event("health", Map.of("pokemon", "foe", "before_fraction", 1, "after_fraction", 0.6));
            BattleTrace.event("move_used", Map.of("pokemon", "foe", "move", "earthquake"));
            BattleTrace.event("health", Map.of("pokemon", "own", "before_fraction", 1, "after_fraction", 0));
            BattleTrace.event("message", Map.of("key", "cobblemon.battle.fainted", "args", List.of("Gengar"), "pokemon_refs", Map.of("0", "own")));
            BattleTrace.event("message", Map.of("key", "cobblemon.battle.win", "args", List.of("Bertha")));
            BattleTrace.event("message", Map.of("key", "cobblemon.battle.win", "args", List.of("Bertha")));
            BattleTrace.sync(null, Map.of());
            Path summary;
            try (var paths = Files.list(root.resolve("battle-logs"))) {
                summary = paths.filter(p -> p.toString().endsWith(".summary.json")).findFirst().orElseThrow();
            }
            var report = JsonParser.parseString(Files.readString(summary)).getAsJsonObject();
            check(report.get("outcome").getAsString().equals("loss"), "Enemy victory must be an explicit loss");
            check(report.get("control").getAsString().equals("auto_assisted"), "AUTO activity must be distinguishable from manual battles");
            var counts = report.getAsJsonObject("counts");
            check(counts.get("unattributed_moves").getAsInt() == 1, "Ambiguous move users must be logged without creating a fictitious Pokemon");
            check(counts.get("ally_faints").getAsInt() == 1, "Zero HP and a faint message must not double-count one faint");
            check(counts.get("ko_prediction_not_confirmed").getAsInt() == 1, "A nonlethal predicted KO must be reviewable even after a faster enemy move");
            check(counts.get("damage_after_auto_switch").getAsInt() >= 1 && counts.get("switches_without_an_attack").getAsInt() == 1,
                    "Switch chains and damage taken on entry must have evidence");
            check(report.getAsJsonArray("pokemon").size() == 3, "Enemy and benched friendly Pokemon must survive in the team history");
            check(report.getAsJsonObject("context").has("assumed_rosters"), "Assumed roster data must stay separate from observed teams");
            check(Files.readAllLines(root.resolve("battle-summary.csv")).size() == 2, "Result and close messages update one row rather than adding duplicate battles");
            UUID manual = UUID.randomUUID();
            BattleTrace.sync(manual, Map.of("allies", "Player", "enemies", "Bertha", "kind", "trainer"));
            BattleTrace.event("message", Map.of("key", "cobblemon.battle.win", "args", List.of("Player")));
            BattleTrace.sync(null, Map.of());
            String index = Files.readString(root.resolve("battle-summary.csv"));
            check(index.contains("\"manual\",\"win\""), "Manual-only wins must be logged");
            BattleTrace.sync(UUID.randomUUID(), Map.of("allies", "Player", "enemies", "=bad,trainer", "kind", "trainer"));
            BattleTrace.sync(null, Map.of());
            index = Files.readString(root.resolve("battle-summary.csv"));
            check(index.contains("\"unresolved\"") && index.contains("'=bad,trainer"), "Unfinished battles must remain unresolved and names must be CSV safe");
            check(Files.readAllLines(root.resolve("battle-summary.csv")).size() == 4, "Every battle must have one index entry");
            var all = new TrainerBattleStats(root, "trainer-all-battle-stats", false);
            all.start(manual, "new-version", "Bertha", "Player", "Bertha");
            all.message("cobblemon.battle.win", "Player"); all.message("cobblemon.battle.win", "Player");
            check(Files.readString(root.resolve("trainer-all-battle-stats.csv")).contains("\"Bertha\",1,100.00%"), "All-battle trainer totals include manual wins once");
            System.out.println("All-battle summaries, manual wins, team history, ability evidence, switch cost, KO discrepancies and unresolved-outcome checks passed.");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally {
            BattleTrace.sync(null, Map.of());
            if (root != null) try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
