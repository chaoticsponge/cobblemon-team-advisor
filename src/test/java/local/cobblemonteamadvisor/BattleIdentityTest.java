package local.cobblemonteamadvisor;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BattleIdentityTest {
    static void run() {
        var own = new BattleIdentity.Candidate("own", "p1", "Player", "Gengar");
        var enemy = new BattleIdentity.Candidate("enemy", "p2", "Rival", "Gengar");
        var both = List.of(own, enemy);
        check(BattleIdentity.match("Rival's Gengar", "Rival", "Gengar", both).equals(List.of(enemy)), "Owned messages must identify the enemy despite identical species");
        check(BattleIdentity.match("Gengar de Rival", "Rival", "Gengar", both).equals(List.of(enemy)), "Ownership must work independently of localized possessive wording");
        check(BattleIdentity.match("Gengar", null, null, both).size() == 2, "An ambiguous unowned name must stay ambiguous");
        check(BattleIdentity.match("Rival's Gengar", "Unknown", "Gengar", both).isEmpty(), "A wrong owner must not fall back to a species guess");
        check(BattleIdentity.match("Gengar", null, null, List.of(enemy)).equals(List.of(enemy)), "Unique wild/plain Pokemon names must remain resolvable");
        var state = new BattleReport();
        state.accept("battle_snapshot", new Gson().toJsonTree(Map.of("assumed_rosters", Map.of("p2", List.of("known assumption")))).getAsJsonObject(), 1, 1);
        state.accept("battle_snapshot", new Gson().toJsonTree(Map.of("assumed_rosters", Map.of())).getAsJsonObject(), 1, 2);
        check(state.summary(false).getAsJsonObject("context").getAsJsonObject("assumed_rosters").getAsJsonArray("p2").size() == 1,
                "The final faint must not erase earlier trainer assumptions");
        state.accept("battle_snapshot", new Gson().toJsonTree(Map.of("assumed_rosters", Map.of("p2", List.of()))).getAsJsonObject(), 2, 3);
        check(state.summary(false).getAsJsonObject("context").getAsJsonObject("assumed_rosters").getAsJsonArray("p2").isEmpty(),
                "An explicit roster contradiction must still clear the assumption");
        replay();
        System.out.println("Owned Pokemon identity, duplicate-species PvP, localized names, real-shape report recovery and per-Pokemon CSV checks passed.");
    }

    private static void replay() {
        var rows = new ArrayList<JsonObject>();
        rows.add(row("battle_start", Map.of("allies", "Player", "enemies", "Rival", "kind", "pvp_or_mixed", "actors",
                List.of(Map.of("actor", "p1", "name", "Player"), Map.of("actor", "p2", "name", "Rival")))));
        rows.add(row("battle_snapshot", Map.of("pokemon", List.of(
                Map.of("pokemon", "own", "actor", "p1", "side", "ally", "source", "observed_active", "name", "Gengar", "apparent_species", "Gengar", "level", 50),
                Map.of("pokemon", "enemy", "actor", "p2", "side", "enemy", "source", "observed_active", "name", "Gengar", "apparent_species", "Gengar", "level", 50)),
                "assumed_rosters", Map.of("p2", List.of(Map.of("species", "gengar", "level", 50, "moveset", List.of("shadowball", "sludgebomb")))))));
        rows.add(row("move_used", Map.of("pokemon", "", "move", "shadowball", "source_text", "Rival's Gengar")));
        rows.add(row("message", Map.of("key", "cobblemon.battle.used_move", "args", List.of("Rival's Gengar", "Shadow Ball"))));
        rows.add(row("message", Map.of("key", "cobblemon.battle.owned_pokemon", "text", "Rival's Gengar", "args", List.of("Rival", "Gengar"))));
        rows.add(row("ability_event", Map.of("key", "cobblemon.battle.ability.generic", "args", List.of("Rival's Gengar", "Cursed Body"))));
        rows.add(row("battle_snapshot", Map.of("pokemon", List.of(), "assumed_rosters", Map.of())));
        rows.add(row("message", Map.of("key", "cobblemon.battle.win", "args", List.of("Rival"))));
        rows.add(row("battle_closed", Map.of()));
        String original = rows.toString();
        var review = BattleLogReplay.rebuild(rows);
        check(original.equals(rows.toString()), "Report recovery must not mutate original trace records");
        var foes = review.getAsJsonObject("teams").getAsJsonArray("enemy");
        check(foes.size() == 1 && foes.get(0).getAsJsonObject().getAsJsonArray("moves_used").get(0).getAsString().equals("shadowball"), "The enemy's move must be attached to its UUID and grouped enemy team");
        check(foes.get(0).getAsJsonObject().getAsJsonArray("ability_events").size() == 1, "Literal generic ability messages must attach to the enemy");
        check(BattleReport.number(review.getAsJsonObject("counts"), "unattributed_moves") == 0, "Recovered ownership must eliminate the old unknown-user defect");
        var csv = BattlePokemonCsv.rows(review);
        check(csv.size() == 2 && csv.stream().anyMatch(line -> line.contains("\"enemy\",\"Rival\",\"enemy\",\"Gengar\"")
                && line.contains("shadowball; sludgebomb") && line.contains("Cursed Body")), "CSV must expose enemy, owner, observed ability, used move and separately assumed moves");
        java.nio.file.Path directory = null;
        try {
            directory = Files.createTempDirectory("battle-pokemon-csv");
            BattlePokemonCsv.update(directory, review); BattlePokemonCsv.update(directory, review);
            check(Files.readAllLines(directory.resolve("battle-pokemon.csv")).size() == 3, "Repeated reviews must replace Pokemon rows instead of duplicating them");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally {
            if (directory != null) try { Files.deleteIfExists(directory.resolve("battle-pokemon.csv")); Files.delete(directory); }
            catch (java.io.IOException error) { throw new AssertionError(error); }
        }
    }

    private static JsonObject row(String event, Map<String, ?> data) {
        JsonObject row = new JsonObject(); row.addProperty("mod_version", "1.18.0"); row.addProperty("battle", "recorded-battle");
        row.addProperty("event", event); row.addProperty("turn", 1); row.addProperty("sequence", 1); row.add("data", new Gson().toJsonTree(data));
        return row;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
