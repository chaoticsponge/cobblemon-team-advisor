package local.cobblemonteamadvisor;

import java.util.Arrays;
import java.util.List;

final class TrainerWinEstimatorTest {
    static void run() {
        var strong = fighter(100, 100, 100);
        var weak = fighter(100, 100, 20);
        var hit = attack("hit", 200, 1, 0, 20, false);
        var small = attack("small", 10, 1, 0, 20, false);
        require(estimate(List.of(strong, weak), 1, List.of(hit, small)).wins() == 128, "A faster certain KO wins every modeled trial");
        require(estimate(List.of(strong, weak), 1, List.of(small, hit)).losses() == 128, "A weak attack must not look like a winning matchup");
        require(estimate(List.of(fighter(5, 100, 100), weak), 1, List.of(small, small)).losses() == 128, "Current HP must affect the result");
        var coin = estimate(List.of(strong, weak), 1, List.of(attack("miss", 200, 0.5, 0, 20, false), hit));
        require(coin.wins() > 40 && coin.wins() < 90, "Accuracy must produce losses despite sufficient on-hit damage");
        require(estimate(List.of(strong, weak), 1, List.of(hit, attack("priority", 200, 1, 1, 20, false))).losses() == 128,
                "Priority must beat higher speed");
        require(estimate(List.of(strong, weak), 1, List.of(attack("empty", 200, 1, 0, 0, false), hit)).losses() == 128,
                "Exhausted moves must not attack");
        var opener = estimate(List.of(strong, fighter(100, 100, 20)), 1,
                List.of(attack("firstimpression", 60, 1, 2, 10, true), attack("foe", 40, 1, 0, 20, false)));
        require(opener.losses() == 128, "First-turn-only moves must not repeat until victory");
        var team = estimate(List.of(strong, fighter(100, 100, 20), fighter(100, 100, 20)), 1,
                List.of(attack("attack", 60, 1, 0, 20, false), attack("foe", 60, 1, 0, 20, false), attack("foe", 60, 1, 0, 20, false)));
        require(team.losses() == 128, "HP must carry between opponents; beating the lead does not imply beating the full team");
        require(estimate(List.of(strong, weak), 1, List.of(attack("immune", 0, 1, 0, 20, false), attack("immune", 0, 1, 0, 20, false)))
                .label().equals("Est. win: uncertain"), "Stalled trials must not invent a win percentage");
        require(!coin.label().contains("100%"), "Labels must not present sampled outcomes as guaranteed wins");
        require(TrainerWinEstimator.bucket(0.5) == 2 && TrainerWinEstimator.bucket(0.51) == 1, "Brine's half-health boundary must remain distinct");
        require(!TrainerRoster.preview("Ace Trainer Abel", "ace_trainer_abel_04a5").isEmpty(), "Synced IDs must resolve a full team");
        require(TrainerRoster.preview("Ace Trainer Abel", "custom_server_trainer").isEmpty(), "Unknown IDs must not borrow an unrelated roster");
        require(TrainerRoster.preview("Unknown NPC", null).isEmpty(), "Unknown names must not fabricate teams");
        try (var input = TrainerWinEstimatorTest.class.getResourceAsStream("/trainer-roster-ids.json")) {
            var index = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            require(index.size() == 1559, "The complete bundled trainer ID index must load");
            for (String id : index.keySet()) require(!TrainerRoster.preview("", id).isEmpty(), "Every trainer ID must resolve its existing roster");
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
        System.out.println("Trainer preview HP, team attrition, accuracy, priority, PP, opener and identity checks passed.");
    }

    private static TrainerWinEstimator.Fighter fighter(double hp, double max, double speed) {
        return new TrainerWinEstimator.Fighter(hp, max, speed, 0, "", false);
    }

    private static TrainerWinEstimator.Attack attack(String id, double damage, double accuracy, int priority, int pp, boolean opener) {
        double[] table = new double[25]; Arrays.fill(table, damage);
        return new TrainerWinEstimator.Attack(new BattlePlanner.Action(id, damage, accuracy, priority, pp,
                BattlePlanner.Effect.NONE, opener, false, true), table);
    }

    private static TrainerWinEstimator.Estimate estimate(List<TrainerWinEstimator.Fighter> fighters, int allies,
                                                        List<TrainerWinEstimator.Attack> moves) {
        @SuppressWarnings("unchecked")
        List<TrainerWinEstimator.Attack>[][] attacks = new List[fighters.size()][fighters.size()];
        for (int i = 0; i < fighters.size(); i++) for (int j = 0; j < fighters.size(); j++) attacks[i][j] = List.of(moves.get(i));
        return TrainerWinEstimator.estimate(new TrainerWinEstimator.Model(fighters, allies, attacks, 0));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
