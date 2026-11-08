package local.cobblemonteamadvisor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repair only attribution recoverable from recorded structured ownership; original traces are untouched. */
public final class BattleLogReplay {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Provide a battle-logs directory");
        try (var files = Files.list(Path.of(args[0]))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                var rows = Files.readAllLines(path).stream().filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
                if (rows.isEmpty() || !BattleReport.text(rows.getFirst(), "mod_version").equals("1.18.0")
                        || rows.stream().noneMatch(r -> BattleReport.text(r, "event").equals("battle_closed"))) continue;
                JsonObject review = rebuild(rows);
                review.addProperty("trace", path.getFileName().toString());
                Path summary = path.resolveSibling(path.getFileName().toString().replace(".jsonl", ".summary.json"));
                Path backup = summary.resolveSibling(summary.getFileName().toString().replace(".summary.json", ".summary.before-1.18.1.json"));
                if (Files.exists(summary) && !Files.exists(backup)) Files.copy(summary, backup);
                BattlePokemonCsv.writeAtomic(summary, new GsonBuilder().serializeNulls().create().toJson(review));
                BattlePokemonCsv.update(path.getParent().getParent(), review);
                System.out.println("Rebuilt " + path.getFileName() + ": " + BattleReport.number(review, "attributions_recovered") + " attributions");
            }
        }
    }

    static JsonObject rebuild(List<JsonObject> rows) {
        Map<String, String> owners = new LinkedHashMap<>(), sides = new LinkedHashMap<>();
        Map<String, BattleIdentity.Candidate> known = new LinkedHashMap<>();
        Map<String, Set<String>> aliases = new LinkedHashMap<>();
        List<BattleIdentity.Candidate> active = new ArrayList<>();
        for (var row : rows) {
            var data = BattleReport.object(row, "data");
            if (BattleReport.text(row, "event").equals("battle_start")) for (var actor : BattleReport.array(data, "actors")) {
                var a = actor.getAsJsonObject(); owners.put(BattleReport.text(a, "actor"), BattleReport.text(a, "name"));
            }
            if (BattleReport.text(row, "event").equals("battle_snapshot")) {
                active.clear();
                for (var entry : BattleReport.array(data, "pokemon")) {
                    var p = entry.getAsJsonObject(); String id = BattleReport.text(p, "pokemon");
                    sides.put(id, BattleReport.text(p, "side"));
                    if (!BattleReport.text(p, "source").equals("observed_active")) continue;
                    String actor = BattleReport.text(p, "actor");
                    var candidate = new BattleIdentity.Candidate(id, actor, owners.getOrDefault(actor, ""), BattleReport.text(p, "name"));
                    active.add(candidate); known.put(id, candidate);
                }
            }
            if (BattleReport.text(data, "key").equals("cobblemon.battle.owned_pokemon")) {
                var args = BattleReport.array(data, "args");
                if (args.size() != 2) continue;
                var matches = BattleIdentity.match("", args.get(0).getAsString(), args.get(1).getAsString(), active);
                if (matches.size() == 1) aliases.computeIfAbsent(BattleReport.text(data, "text"), ignored -> new LinkedHashSet<>()).add(matches.getFirst().pokemon());
            }
        }
        BattleReport report = new BattleReport(); int recovered = 0;
        for (var row : rows) {
            var data = BattleReport.object(row, "data").deepCopy();
            String type = BattleReport.text(row, "event");
            if (type.equals("move_used") && BattleReport.text(data, "pokemon").isEmpty()) {
                String id = unique(aliases, BattleReport.text(data, "source_text"));
                if (id != null) {
                    data.addProperty("pokemon", id); data.addProperty("side", sides.getOrDefault(id, "unknown"));
                    data.addProperty("actor", known.get(id).actor()); data.addProperty("attribution_source", "recorded_owned_pokemon_messages"); recovered++;
                }
            }
            if (type.equals("message") || type.equals("ability_event")) {
                JsonObject refs = BattleReport.object(data, "pokemon_refs").deepCopy(); var args = BattleReport.array(data, "args");
                for (int i = 0; i < args.size(); i++) {
                    String id = unique(aliases, args.get(i).getAsString());
                    if (id != null && !refs.has(Integer.toString(i))) { refs.addProperty(Integer.toString(i), id); recovered++; }
                }
                data.add("pokemon_refs", refs);
                if (BattleReport.text(data, "key").equals("cobblemon.battle.ability.generic") && args.size() > 1)
                    data.addProperty("ability_display_name", args.get(1).getAsString());
            }
            report.accept(type, data, (int) BattleReport.number(row, "turn"), (long) BattleReport.number(row, "sequence"));
        }
        JsonObject review = report.summary(true);
        review.addProperty("mod_version", BattleReport.text(rows.getFirst(), "mod_version"));
        review.addProperty("battle", BattleReport.text(rows.getFirst(), "battle"));
        review.addProperty("attributions_recovered", recovered);
        review.addProperty("review_revision", "owned-name-attribution-v1");
        return review;
    }

    private static String unique(Map<String, Set<String>> aliases, String text) {
        var ids = aliases.get(text); return ids != null && ids.size() == 1 ? ids.iterator().next() : null;
    }
}
