package local.cobblemonteamadvisor;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Local completed auto-battle totals; integer counts remain authoritative across releases. */
final class TrainerBattleStats {
    private static final Gson GSON = new Gson();
    private final Path directory;
    private final List<Total> totals = new ArrayList<>();
    private UUID battle;
    private String version, trainer, allies, enemies;
    private boolean automated, finished;

    record Total(String version, String trainer, long battles, long wins) { }

    TrainerBattleStats(Path directory) throws IOException {
        this.directory = directory;
        Path state = directory.resolve("trainer-battle-stats.json");
        if (Files.exists(state)) {
            try {
                Total[] saved = GSON.fromJson(Files.readString(state), Total[].class);
                if (saved == null) throw new IllegalArgumentException("Empty statistics state");
                for (Total total : saved) {
                    if (total == null || total.version == null || total.trainer == null
                            || total.battles <= 0 || total.wins < 0 || total.wins > total.battles)
                        throw new IllegalArgumentException("Invalid statistics counts");
                    totals.add(total);
                }
            } catch (JsonParseException | IllegalArgumentException error) {
                throw new IOException("Cannot read trainer statistics; preserving existing files", error);
            }
        }
        save();
    }

    void start(UUID id, String modVersion, String trainerName, String allyNames, String enemyNames) {
        if (Objects.equals(battle, id)) return;
        battle = id;
        version = modVersion;
        trainer = trainerName;
        allies = allyNames;
        enemies = enemyNames;
        automated = false;
        finished = false;
    }

    void automatedAction() { if (battle != null) automated = true; }

    void message(String key, String actorNames) throws IOException {
        if (battle == null || trainer == null || !automated || finished || Objects.equals(allies, enemies)) return;
        Boolean won = outcome(key, actorNames, allies, enemies);
        if (won == null) return;
        // Mark before saving: duplicate result packets must never add another battle after an I/O failure.
        finished = true;
        for (int i = 0; i < totals.size(); i++) {
            Total total = totals.get(i);
            if (total.version.equals(version) && total.trainer.equals(trainer)) {
                totals.set(i, new Total(version, trainer, total.battles + 1, total.wins + (won ? 1 : 0)));
                save();
                return;
            }
        }
        totals.add(new Total(version, trainer, 1, won ? 1 : 0));
        save();
    }

    static Boolean outcome(String key, String names, String allies, String enemies) {
        if ("cobblemon.battle.win".equals(key)) {
            if (Objects.equals(names, allies)) return true;
            if (Objects.equals(names, enemies)) return false;
        } else if ("cobblemon.battle.lose".equals(key)) {
            if (Objects.equals(names, allies)) return false;
            if (Objects.equals(names, enemies)) return true;
        }
        return null;
    }

    private void save() throws IOException {
        write("trainer-battle-stats.json", GSON.toJson(totals));
        StringBuilder csv = new StringBuilder("mod_version,trainer_name,number_of_battles,win_rate\n");
        for (Total total : totals) {
            csv.append(cell(total.version)).append(',').append(cell(total.trainer)).append(',')
                    .append(total.battles).append(',')
                    .append(String.format(Locale.ROOT, "%.2f%%", 100.0 * total.wins / total.battles)).append('\n');
        }
        write("trainer-battle-stats.csv", csv.toString());
    }

    private void write(String filename, String content) throws IOException {
        Path target = directory.resolve(filename);
        Path temporary = Files.createTempFile(directory, filename, ".tmp");
        try {
            Files.writeString(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String cell(String value) {
        // Quoting alone does not stop spreadsheet formulas in server-provided trainer names.
        String text = value.replaceAll("[\\p{Cntrl}§]", " ").strip();
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
