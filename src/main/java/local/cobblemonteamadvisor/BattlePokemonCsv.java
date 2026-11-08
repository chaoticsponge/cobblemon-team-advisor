package local.cobblemonteamadvisor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** One easy-to-filter row per encountered Pokemon; never substitutes assumed roster moves. */
final class BattlePokemonCsv {
    private static final String HEADER = "mod_version,battle,outcome,control,side,owner,pokemon_uuid,species,species_source,level,known_moves,assumed_moves,moves_used,ability,abilities_observed,item,last_hp_fraction";

    static List<String> rows(JsonObject review) {
        String version = BattleReport.text(review, "mod_version"), battle = BattleReport.text(review, "battle");
        List<String> rows = new ArrayList<>();
        for (var value : BattleReport.array(review, "pokemon")) {
            JsonObject p = value.getAsJsonObject();
            String species = BattleReport.text(p, "species"), owner = BattleReport.text(p, "owner");
            if (owner.isEmpty()) for (var actor : BattleReport.array(BattleReport.object(review, "context"), "actors")) {
                var a = actor.getAsJsonObject();
                if (BattleReport.text(a, "actor").equals(BattleReport.text(p, "actor"))) owner = BattleReport.text(a, "name");
            }
            var known = new LinkedHashSet<String>();
            for (var move : BattleReport.array(p, "moves")) if (move.isJsonObject()) known.add(BattleReport.text(move.getAsJsonObject(), "move"));
            for (var move : BattleReport.array(p, "synced_moves")) if (move.isJsonPrimitive()) known.add(move.getAsString());
            var assumed = new ArrayList<JsonObject>();
            var rosters = BattleReport.object(BattleReport.object(review, "context"), "assumed_rosters");
            String actorId = BattleReport.text(p, "actor");
            String visibleSpecies = species.isEmpty() ? BattleReport.text(p, "apparent_species") : species;
            if (rosters.has(actorId) && rosters.get(actorId).isJsonArray()) for (var member : rosters.getAsJsonArray(actorId)) {
                var m = member.getAsJsonObject();
                if (normalize(BattleReport.text(m, "species")).equals(normalize(visibleSpecies))
                        && BattleReport.number(m, "level") == BattleReport.number(p, "level")) assumed.add(m);
            }
            String assumedMoves = assumed.size() == 1 ? join(BattleReport.array(assumed.getFirst(), "moveset")) : "";
            var abilities = new LinkedHashSet<String>();
            for (var event : BattleReport.array(p, "ability_events")) {
                var e = event.getAsJsonObject();
                if (!BattleReport.text(BattleReport.object(e, "pokemon_refs"), "0").equals(BattleReport.text(p, "pokemon"))) continue;
                for (var ability : BattleReport.array(e, "abilities_mentioned")) abilities.add(ability.getAsString());
                String display = BattleReport.text(e, "ability_display_name"); if (!display.isEmpty() && abilities.stream().noneMatch(a -> normalize(a).equals(normalize(display)))) abilities.add(display);
            }
            String ability = BattleReport.text(p, "ability"); if (ability.isEmpty()) ability = BattleReport.text(p, "synced_ability");
            String item = BattleReport.text(p, "item"); if (item.isEmpty()) item = BattleReport.text(p, "synced_item");
            String hp = BattleReport.text(p, "last_observed_hp_fraction"); if (hp.isEmpty()) hp = BattleReport.text(p, "hp_fraction");
            rows.add(List.of(version, battle, BattleReport.text(review, "outcome"), BattleReport.text(review, "control"),
                    BattleReport.text(p, "side"), owner, BattleReport.text(p, "pokemon"),
                    species.isEmpty() ? BattleReport.text(p, "apparent_species") : species,
                    species.isEmpty() ? "apparent_battle_species" : "synced_party", BattleReport.text(p, "level"),
                    String.join("; ", known), assumedMoves, join(BattleReport.array(p, "moves_used")), ability, String.join("; ", abilities), item, hp)
                    .stream().map(TrainerBattleStats::cell).collect(java.util.stream.Collectors.joining(",")));
        }
        return rows;
    }

    static void update(Path profile, JsonObject review) throws IOException {
        Path file = profile.resolve("battle-pokemon.csv");
        var lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<String>();
        if (lines.isEmpty()) lines.add(HEADER);
        if (!lines.getFirst().equals(HEADER)) throw new IOException("Unexpected battle-pokemon.csv header; preserving existing file");
        String prefix = TrainerBattleStats.cell(BattleReport.text(review, "mod_version")) + "," + TrainerBattleStats.cell(BattleReport.text(review, "battle")) + ",";
        lines.removeIf(line -> line.startsWith(prefix)); lines.addAll(rows(review));
        writeAtomic(file, String.join("\n", lines) + "\n");
    }

    static void writeAtomic(Path file, String content) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "battle-pokemon-", ".tmp");
        try {
            Files.writeString(temporary, content);
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String normalize(String value) { return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    private static String join(JsonArray values) {
        List<String> text = new ArrayList<>(); for (var value : values) if (value.isJsonPrimitive()) text.add(value.getAsString());
        return String.join("; ", text);
    }
}
