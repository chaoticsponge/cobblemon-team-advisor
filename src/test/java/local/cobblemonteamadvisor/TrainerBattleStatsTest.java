package local.cobblemonteamadvisor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

final class TrainerBattleStatsTest {
    static void run() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("trainer-stats-test");
            TrainerBattleStats stats = new TrainerBattleStats(directory);
            Path csv = directory.resolve("trainer-battle-stats.csv");
            require(Files.readString(csv).equals("mod_version,trainer_name,number_of_battles,win_rate\n"), "Create an empty CSV without invented history");
            UUID first = UUID.randomUUID();
            stats.start(first, "1.13.0", "Brock", "Player", "Brock");
            stats.message("cobblemon.battle.win", "Player");
            require(Files.readAllLines(csv).size() == 1, "Manual-only battles must not affect AUTO win rates");
            stats.automatedAction();
            stats.start(first, "1.13.0", "Brock", "Player", "Brock");
            stats.message("cobblemon.battle.win", "Player");
            stats.message("cobblemon.battle.win", "Player");
            require(Files.readString(csv).contains("\"1.13.0\",\"Brock\",1,100.00%"), "Repeated sync and victory messages count once");
            stats.start(UUID.randomUUID(), "1.13.0", "Brock", "Player", "Brock");
            stats.automatedAction();
            stats.message("cobblemon.battle.win", "Brock");
            require(Files.readString(csv).contains("\"Brock\",2,50.00%"), "Enemy victory is a loss");
            stats.start(UUID.randomUUID(), "1.13.0", "Brock", "Player", "Brock");
            stats.automatedAction();
            stats.message("cobblemon.battle.flee", "Player");
            stats.start(null, "1.13.0", null, null, null);
            stats.message("cobblemon.battle.win", "Player");
            require(Files.readString(csv).contains("\"Brock\",2,50.00%"), "Unfinished or disconnected battles are not losses");
            stats.start(UUID.randomUUID(), "1.13.0", null, "Player", "Wild");
            stats.automatedAction();
            stats.message("cobblemon.battle.win", "Player");
            require(Files.readAllLines(csv).size() == 2, "Non-trainer battles are excluded");
            stats = new TrainerBattleStats(directory);
            stats.start(UUID.randomUUID(), "1.13.0", "Brock", "Player", "Brock");
            stats.automatedAction();
            stats.message("cobblemon.battle.lose", "Brock");
            require(Files.readString(csv).contains("\"1.13.0\",\"Brock\",3,66.67%"), "Restarted counts must use integer wins, not rounded percentages");
            stats.start(UUID.randomUUID(), "1.13.1", "Brock", "Player", "Brock");
            stats.automatedAction();
            stats.message("cobblemon.battle.lose", "Player");
            require(Files.readString(csv).contains("\"1.13.0\",\"Brock\",3,66.67%"), "Upgrades preserve previous version totals");
            require(Files.readString(csv).contains("\"1.13.1\",\"Brock\",1,0.00%"), "New versions have separate totals");
            require(Boolean.TRUE.equals(TrainerBattleStats.outcome("cobblemon.battle.win", "Player & Ally", "Player & Ally", "Brock & Misty")), "Combined side names identify team wins");
            require(TrainerBattleStats.outcome("cobblemon.battle.win", "Player", "PlayerTwo", "Brock") == null, "Partial names must not match");
            require(TrainerBattleStats.cell("=HYPERLINK(\"bad\")").equals("\"'=HYPERLINK(\"\"bad\"\")\""), "CSV must escape quotes and neutralize formulas");
            require(TrainerBattleStats.cell("Brock, Gym\nLeader").equals("\"Brock, Gym Leader\""), "Trainer names cannot inject CSV rows");
            String before = Files.readString(csv);
            Files.writeString(directory.resolve("trainer-battle-stats.json"), "broken state");
            try {
                new TrainerBattleStats(directory);
                throw new AssertionError("Corrupt state must fail without resetting history");
            } catch (IOException expected) {
                require(Files.readString(csv).equals(before), "Corrupt state must not overwrite CSV history");
            }
            System.out.println("Trainer win-rate persistence, outcome attribution and CSV safety checks passed.");
        } catch (IOException error) {
            throw new AssertionError(error);
        } finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                } catch (IOException error) {
                    throw new AssertionError(error);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
