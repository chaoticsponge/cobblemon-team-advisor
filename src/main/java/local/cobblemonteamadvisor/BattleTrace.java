package local.cobblemonteamadvisor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One local JSONL file per battle; names never become paths and ordinary chat is never recorded. */
final class BattleTrace {
    private static final JsonSerializer<Number> NUMBER = (value, type, context) ->
            Double.isFinite(value.doubleValue()) ? new JsonPrimitive(value) : JsonNull.INSTANCE;
    private static final Gson GSON = new GsonBuilder().serializeNulls().registerTypeAdapter(double.class, NUMBER)
            .registerTypeAdapter(Double.class, NUMBER).registerTypeAdapter(float.class, NUMBER)
            .registerTypeAdapter(Float.class, NUMBER).create();
    private static BattleTrace instance;
    private final Path directory;
    private final String version;
    private final Set<String> recorded = new HashSet<>();
    private UUID battle;
    private int turn;
    private BufferedWriter writer;
    private Path reportFile;
    private BattleReport report;
    private long sequence;
    private String snapshot = "";
    private String indexedRow;

    BattleTrace(Path directory, String version) { this.directory = directory; this.version = version; }

    static void initialize(Path profile, String version) { instance = new BattleTrace(profile.resolve("battle-logs"), version); }

    static void sync(UUID battle, Map<String, ?> context) {
        if (instance != null) instance.start(battle, context);
    }

    static void event(String type, Map<String, ?> data) {
        if (instance != null) instance.write(type, data);
    }

    static void snapshot(Map<String, ?> data) {
        if (instance == null || instance.writer == null) return;
        String json = GSON.toJson(data);
        if (!json.equals(instance.snapshot)) { instance.snapshot = json; instance.write("battle_snapshot", data); }
    }

    static void evaluation(String key, String type, Map<String, ?> data) {
        if (instance != null && instance.battle != null && instance.recorded.add(instance.turn + ":" + key)) {
            instance.write(type, data);
        }
    }

    static void turn(int number) { if (instance != null) instance.turn = number; }

    void start(UUID id, Map<String, ?> context) {
        if (Objects.equals(battle, id)) return;
        if (writer != null) {
            write("battle_closed", Map.of("reason", "client battle changed or ended; see outcome messages"));
            close();
        }
        battle = id;
        turn = 0;
        recorded.clear();
        report = new BattleReport(); sequence = 0; snapshot = ""; indexedRow = null;
        if (id == null) return;
        try {
            Files.createDirectories(directory);
            String stem = Instant.now().toEpochMilli() + "-" + id;
            reportFile = directory.resolve(stem + ".summary.json");
            writer = Files.newBufferedWriter(directory.resolve(stem + ".jsonl"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            write("battle_start", context);
        } catch (IOException error) {
            fail(error);
        }
    }

    void write(String type, Map<String, ?> data) {
        if (writer == null) return;
        try {
            JsonObject row = new JsonObject();
            row.addProperty("time", Instant.now().toString());
            row.addProperty("mod_version", version);
            row.addProperty("battle", battle.toString());
            row.addProperty("turn", turn);
            row.addProperty("sequence", ++sequence);
            row.addProperty("event", type);
            JsonObject payload = GSON.toJsonTree(data).getAsJsonObject();
            row.add("data", payload);
            writer.write(GSON.toJson(row));
            writer.newLine();
            writer.flush(); // A running battle can be inspected without waiting for completion.
            try {
                report.accept(type, payload, turn, sequence);
                if (type.equals("battle_start") || type.equals("battle_closed") || report.completed()
                        || type.startsWith("auto_") && type.endsWith("submitted") || type.equals("auto_item_requested") || type.equals("message")
                        && BattleReport.text(payload, "key").equals("cobblemon.battle.turn")) saveReport(type.equals("battle_closed"));
            } catch (IOException | RuntimeException error) {
                System.getLogger(BattleTrace.class.getName()).log(System.Logger.Level.WARNING, "Could not update battle review; raw trace remains available", error);
            }
        } catch (IOException | RuntimeException error) {
            fail(error);
        }
    }

    private void saveReport(boolean closed) throws IOException {
        JsonObject review = report.summary(closed);
        review.addProperty("mod_version", version); review.addProperty("battle", battle.toString());
        review.addProperty("trace", reportFile.getFileName().toString().replace(".summary.json", ".jsonl"));
        Path temporary = Files.createTempFile(directory, "battle-review-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(review));
            try { Files.move(temporary, reportFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(temporary, reportFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        JsonObject context = review.getAsJsonObject("context");
        Path index = directory.getParent().resolve("battle-summary.csv");
        String header = "mod_version,battle,opponents,kind,control,outcome,turns,report";
        String prefix = TrainerBattleStats.cell(version) + "," + TrainerBattleStats.cell(battle.toString()) + ",";
        String row = prefix + String.join(",", TrainerBattleStats.cell(BattleReport.text(context, "enemies")),
                TrainerBattleStats.cell(BattleReport.text(context, "kind")), TrainerBattleStats.cell(report.mode()),
                TrainerBattleStats.cell(report.outcome()), review.get("turns_observed").getAsString(),
                TrainerBattleStats.cell("battle-logs/" + reportFile.getFileName()));
        if (!row.equals(indexedRow)) {
            var lines = Files.exists(index) ? new java.util.ArrayList<>(Files.readAllLines(index)) : new java.util.ArrayList<String>();
            if (lines.isEmpty()) lines.add(header);
            if (!lines.getFirst().equals(header)) throw new IOException("Unexpected battle-summary.csv header; preserving existing file");
            lines.removeIf(line -> line.startsWith(prefix)); lines.add(row);
            Path staged = Files.createTempFile(directory.getParent(), "battle-index-", ".tmp");
            try {
                Files.writeString(staged, String.join("\n", lines) + "\n");
                try { Files.move(staged, index, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(staged, index, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(staged); }
            indexedRow = row;
        }
        BattlePokemonCsv.update(directory.getParent(), review);
    }

    private void fail(Exception error) {
        System.getLogger(BattleTrace.class.getName()).log(System.Logger.Level.WARNING, "Could not write local battle trace", error);
        close();
    }

    private void close() {
        try { if (writer != null) writer.close(); }
        catch (IOException error) { System.getLogger(BattleTrace.class.getName()).log(System.Logger.Level.WARNING, "Could not close battle trace", error); }
        finally { writer = null; }
    }
}
