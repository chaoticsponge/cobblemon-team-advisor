package local.cobblemonteamadvisor;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class PokedexGuideTest {
    static void run() {
        check(TypeMatchups.multiplier("rock", List.of("fire", "flying")) == 4, "Charizard must show a fourfold Rock weakness");
        check(TypeMatchups.multiplier("grass", List.of("fire", "flying")) == 0.25, "Dual resistance must show quarter damage");
        check(TypeMatchups.multiplier("electric", List.of("water", "ground")) == 0, "Ground immunity must override Water weakness");
        check(TypeMatchups.multiplier("ice", List.of("water", "flying")) == 1, "Cancelled weakness must not appear in either defensive group");
        check(TypeMatchups.multiplier("ghost", List.of("normal")) == 0, "The shared battle type chart must preserve immunities");

        EvolutionGuide guide = EvolutionGuide.load();
        check(guide.next("cobblemon:bulbasaur", "Normal").entries().getFirst().requirements().contains("Lv. 16+"), "Show the actual level threshold");
        var eevee = guide.next("cobblemon:eevee", "Normal").entries();
        check(eevee.size() == 8, "Preserve every Eevee branch");
        check(eevee.stream().anyMatch(e -> e.target().equals("Jolteon") && e.requirements().contains("Use Thunder Stone")), "Item evolutions need the interaction item");
        check(eevee.stream().anyMatch(e -> e.target().equals("Umbreon") && e.requirements().contains("Friendship ≥ 160")
                && e.requirements().contains("Night")), "Friendship evolution must retain the time condition");
        check(eevee.stream().anyMatch(e -> e.target().equals("Sylveon") && e.requirements().contains("Fairy move")), "Preserve move-type conditions");
        check(guide.next("cobblemon:eevee", "Gmax").entries().isEmpty(), "An explicit empty form list must not inherit standard evolutions");
        check(guide.next("cobblemon:farfetchd", "Normal").entries().isEmpty(), "Kantonian Farfetch'd must not receive Galarian evolution advice");
        var farfetchd = guide.next("cobblemon:farfetchd", "Galar").entries().getFirst();
        check(farfetchd.target().equals("Sirfetch'd") && farfetchd.requirements().contains("3 critical hits in one battle"), "Regional forms must use their own requirements");
        check(guide.next("cobblemon:primeape", "Normal").entries().getFirst().requirements().contains("Use Rage Fist 20 times"), "Move-use requirements need a readable move name and count");
        check(guide.next("cobblemon:machoke", "Normal").entries().stream().anyMatch(e -> e.requirements().contains("Trade")), "Show trade evolutions");
        check(guide.next("cobblemon:nincada", "Normal").entries().stream().anyMatch(e -> e.target().equals("Shedinja")
                && e.requirements().contains("empty party slot") && e.requirements().contains("Poké Ball")), "Shedinja must not be omitted from the next evolution guide");
        check(!guide.next("custom:unknown", "Normal").known(), "Missing rules must not claim a final evolution");
        check(!guide.next("cobblemon:eevee", "Custom server form").known(), "Unknown server forms must not inherit standard requirements");

        try (var stream = PokedexGuideTest.class.getResourceAsStream("/pokedex-evolutions.json")) {
            var catalog = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("species");
            check(catalog.size() == 1025, "The guide must include all 1,025 reference species");
            for (var species : catalog.entrySet()) {
                check(guide.next(species.getKey(), "Normal").known(), "Every indexed species needs a usable guide");
                for (var form : species.getValue().getAsJsonObject().getAsJsonArray("forms")) {
                    var result = guide.next(species.getKey(), form.getAsJsonObject().get("name").getAsString());
                    check(result.known(), "Every indexed regional form needs a usable guide");
                    for (var entry : result.entries()) check(!entry.requirements().contains("Special condition:"), "Reference conditions must be explained");
                }
            }
        } catch (IOException error) {
            throw new AssertionError("Cannot read evolution catalog", error);
        }

        var scan = new PokedexScanState();
        check(!scan.update("a", true, false), "An incomplete scan must not print a summary");
        check(scan.update("a", true, true), "A completed scan must print once");
        check(!scan.update("a", true, true), "Repeated render ticks and registration confirmations must not spam chat");
        check(!scan.update("b", true, false), "Changing target requires a fresh completed scan");
        check(scan.update("b", true, true), "A second scanned Pokemon needs its own summary");
        check(!scan.update("b", false, true), "A closed scanner must not print from a late confirmation");
        check(scan.update("b", true, true), "A deliberate repeat scan must work after reopening");
        check(!scan.update(null, true, true), "No focus must clear the scan state");
        System.out.println("Private scan state, dual-type matchups and evolution guide checks passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
