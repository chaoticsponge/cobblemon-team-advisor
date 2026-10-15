package local.cobblemonteamadvisor;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

final class BattleTraceTest {
    private record Advice(double score) { }

    static void run() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("battle-trace-test");
            BattleTrace.initialize(directory, "test-version");
            UUID battle = UUID.randomUUID();
            BattleTrace.sync(battle, Map.of("trainer", "Bertha\nnot-a-path/../../"));
            BattleTrace.turn(3);
            BattleTrace.evaluation("candidate", "switch_evaluation", Map.of("allowed", false, "reason", "cannot act"));
            BattleTrace.evaluation("candidate", "switch_evaluation", Map.of("allowed", false));
            BattleTrace.event("health", Map.of("after_fraction", 0.4));
            BattleTrace.event("auto_move_submitted", Map.of("advice", new Advice(Double.NEGATIVE_INFINITY), "raw", Float.NaN));
            Path folder = directory.resolve("battle-logs");
            Path file;
            try (var files = Files.list(folder)) { file = files.findFirst().orElseThrow(); }
            var during = Files.readAllLines(file);
            require(during.size() == 4, "Trace must flush mid-battle and deduplicate render-time evaluations");
            var row = JsonParser.parseString(during.get(1)).getAsJsonObject();
            require(row.get("mod_version").getAsString().equals("test-version") && row.get("turn").getAsInt() == 3,
                    "Each diagnostic row must identify the version and turn");
            require(JsonParser.parseString(during.get(3)).getAsJsonObject().getAsJsonObject("data")
                    .getAsJsonObject("advice").get("score").isJsonNull(), "Non-finite advice must remain valid JSON without breaking battle actions");
            BattleTrace.turn(4);
            BattleTrace.evaluation("candidate", "switch_evaluation", Map.of("allowed", true));
            BattleTrace.sync(null, Map.of());
            require(Files.readAllLines(file).size() == 6, "A new turn permits a fresh evaluation and battle exit closes the trace");
            BattleTrace.sync(UUID.randomUUID(), Map.of());
            BattleTrace.sync(null, Map.of());
            try (var files = Files.list(folder)) { require(files.count() == 2, "Separate battle sessions must not overwrite prior traces"); }
            System.out.println("Local battle trace version, turn, flush, deduplication and JSON safety checks passed.");
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        } finally {
            BattleTrace.sync(null, Map.of());
            if (directory != null) try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
