package local.cobblemonteamadvisor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class BattleQolTest {
    static void run() {
        require("shiny".equals(RareEncounterPolicy.reason(true, false, true, false)), "Caught shiny wild Pokemon must still pause AUTO");
        require("legendary".equals(RareEncounterPolicy.reason(false, true, true, false)), "Caught legendary wild Pokemon must still be protected");
        require("uncaught".equals(RareEncounterPolicy.reason(false, false, false, false)), "An uncaught species must be protected");
        require(RareEncounterPolicy.reason(false, false, true, false) == null, "Ordinary caught wild Pokemon must still battle");
        require(RareEncounterPolicy.reason(true, true, false, true) == null, "Raid bosses require defeat before capture and must not pause");
        List<TeamOptimizer.Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 10; i++) pool.add(candidate("id" + i, "species" + i, i));
        List<String> first = TeamOptimizer.create(pool, Set.of(), 42);
        require(first.size() == 6 && Set.copyOf(first).size() == 6, "An optimizer draft must contain six distinct owned Pokemon");
        List<String> second = TeamOptimizer.create(pool, Set.of(TeamOptimizer.signature(first)), 42);
        require(!TeamOptimizer.signature(first).equals(TeamOptimizer.signature(second)), "Repeated creates should offer another team when alternatives exist");
        require(TeamOptimizer.create(List.of(), Set.of(), 42).isEmpty(), "An empty PC must not invent Pokemon");
        require(TeamOptimizer.create(pool.subList(0, 3), Set.of(), 42).size() == 3, "Small collections must produce only owned Pokemon");
        var sameSpecies = new ArrayList<TeamOptimizer.Candidate>();
        for (int i = 0; i < 6; i++) { var entry = candidate("clone" + i, "same", i); sameSpecies.add(entry); }
        require(TeamOptimizer.create(sameSpecies, Set.of(), 42).size() == 6, "Limited species variety must not leave usable owned slots empty");
        var heal = new BattlePlanner.Action("item:potion", 0, 1, 10, 1,
                new BattlePlanner.Effect(60, 0, 0, 1, 1, 1, 0, "", false, false), false, false, false);
        var hit = BattlePlanner.Action.attack("attack", 80, 0);
        var foe = new BattlePlanner.Combatant(100, 100, 50, 0, 0, "", List.of(BattlePlanner.Action.attack("hit", 30, 0)));
        var sleeping = new BattlePlanner.Combatant(20, 100, 100, 0, 0, "slp", List.of(hit));
        var healed = BattlePlanner.evaluate(sleeping, foe, heal, false, false);
        require(healed.acts() && healed.survives(), "Trainer medicine must be usable while the Pokemon is asleep");
        require(!BattlePlanner.evaluate(sleeping, foe, hit, false, false).acts(), "Pokemon attacks must still respect sleep");
        persistence();
        System.out.println("Rare encounters, balanced loadout drafts, alternatives, medicine turns and saved-loadout checks passed.");
    }

    private static TeamOptimizer.Candidate candidate(String id, String species, int type) {
        double[] offense = new double[18], defense = new double[18];
        Arrays.fill(offense, 25); Arrays.fill(defense, 1); offense[type % 18] = 120; defense[type % 18] = 0;
        return new TeamOptimizer.Candidate(id, species, 100, 100, 100, 100, 100, offense, defense);
    }

    private static void persistence() {
        Path root = null;
        try {
            root = Files.createTempDirectory("optimized-loadouts-test"); Path file = root.resolve("pc.json");
            List<String> ids = java.util.stream.IntStream.range(0, 6).mapToObj(i -> UUID.randomUUID().toString()).toList();
            var entries = List.of(new SavedLoadouts.Entry("Balanced", ids), new SavedLoadouts.Entry("Alternative", List.of(ids.getFirst())));
            SavedLoadouts.save(file, entries);
            require(SavedLoadouts.read(file).equals(entries), "Multiple named teams must survive a restart without changing IDs");
            Files.writeString(file, "broken");
            try { SavedLoadouts.read(file); throw new AssertionError("Corrupt loadouts must not be silently reset"); }
            catch (java.io.IOException expected) { require(Files.readString(file).equals("broken"), "Reading corrupt data must preserve it"); }
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally {
            if (root != null) try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
            catch (java.io.IOException error) { throw new AssertionError(error); }
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
