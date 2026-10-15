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

    BattleTrace(Path directory, String version) { this.directory = directory; this.version = version; }

    static void initialize(Path profile, String version) { instance = new BattleTrace(profile.resolve("battle-logs"), version); }

    static void sync(UUID battle, Map<String, ?> context) {
        if (instance != null) instance.start(battle, context);
    }

    static void event(String type, Map<String, ?> data) {
        if (instance != null) instance.write(type, data);
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
        if (id == null) return;
        try {
            Files.createDirectories(directory);
            writer = Files.newBufferedWriter(directory.resolve(Instant.now().toEpochMilli() + "-" + id + ".jsonl"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
            row.addProperty("event", type);
            row.add("data", GSON.toJsonTree(data));
            writer.write(GSON.toJson(row));
            writer.newLine();
            writer.flush(); // A running battle can be inspected without waiting for completion.
        } catch (IOException | RuntimeException error) {
            fail(error);
        }
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
