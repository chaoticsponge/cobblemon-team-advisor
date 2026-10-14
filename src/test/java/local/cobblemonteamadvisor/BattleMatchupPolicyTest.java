package local.cobblemonteamadvisor;

public final class BattleMatchupPolicyTest {
    public static void main(String[] args) {
        int neutralAttacker = score(4.0D, 1.0D);
        require(score(0.5D, 0.5D) > neutralAttacker, "Resistance must outweigh stronger offence");
        require(score(0.5D, 0.0D) > score(4.0D, 0.5D), "Immunity must beat resistance with stronger offence");
        require(score(1.0D, 0.25D) > score(1.0D, 0.5D), "Double resistance must receive more credit");
        require(score(4.0D, 2.0D) < score(1.0D, 1.0D), "Super-effective offence must not hide a weakness");

        double blocked = BattleMatchupPolicy.effectiveMultiplier(2.0D, "ground", "levitate", null);
        double coverage = BattleMatchupPolicy.effectiveMultiplier(2.0D, "ice", "levitate", null);
        require(blocked == 0.0D, "Levitate must block Ground");
        require(score(1.0D, Math.max(blocked, coverage)) < 0, "Coverage must prevent a false immunity bonus");
        require(BattleMatchupPolicy.effectiveMultiplier(2.0D, "ground", "levitate", "moldbreaker") == 2.0D,
                "Mold Breaker must bypass ability immunity");
        require(BattleMatchupPolicy.effectiveMultiplier(0.0D, "ground", "levitate", "moldbreaker") == 0.0D,
                "Mold Breaker must preserve type immunity");
        require(BattleMatchupPolicy.effectiveMultiplier(2.0D, "ice", "thickfat", null) == 1.0D,
                "Thick Fat must halve damage rather than grant immunity");
        require(BattleMatchupPolicy.effectiveMultiplier(2.0D, "fire", "waterabsorb", null) == 2.0D,
                "An ability must not block unrelated coverage");

        require(BattleMatchupPolicy.shouldSwitch(neutralAttacker, score(0.5D, 0.5D)),
                "A positive current matchup must still switch to a better defensive option");
        require(BattleMatchupPolicy.shouldSwitch(score(4.0D, 2.0D), score(1.0D, 1.0D)),
                "A threatened active Pokemon must switch proactively");
        require(!BattleMatchupPolicy.shouldSwitch(8, 8), "A tie must not switch");
        require(!BattleMatchupPolicy.shouldSwitch(8, 9), "A marginal improvement must not switch");
        require(!BattleMatchupPolicy.shouldSwitch(8, 4), "A worse option must not switch");
        System.out.println("Battle matchup regression checks passed.");
    }

    private static int score(double outgoing, double incoming) {
        return BattleMatchupPolicy.offensiveScore(outgoing) - BattleMatchupPolicy.threatScore(incoming);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
