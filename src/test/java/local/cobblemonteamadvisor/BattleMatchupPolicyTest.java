package local.cobblemonteamadvisor;

import java.util.List;

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
        require(BattleMatchupPolicy.survivalScore(5, 100, 0, 20, 0) + 16
                        < BattleMatchupPolicy.survivalScore(100, 100, 0, 30, 0),
                "A near-fainted resistant candidate must lose to a healthy survivor");
        require(BattleMatchupPolicy.survivalScore(20, 100, 25, 0, 0) < -60,
                "Entry hazards alone can make an immune switch unsafe");
        require(BattleMatchupPolicy.survivalScore(40, 100, 10, 25, 6) == -60,
                "Entry damage plus attack plus residual must be considered together");

        require(BattleMatchupPolicy.actsFirst(1, 20, 0, 100, false), "Priority must override speed");
        require(!BattleMatchupPolicy.actsFirst(0, 100, 1, 20, false), "Enemy priority must prevent a safe KO claim");
        require(!BattleMatchupPolicy.actsFirst(0, 100, 0, 100, false), "Speed ties must not promise a first KO");
        require(BattleMatchupPolicy.actsFirst(0, 20, 0, 100, true), "Trick Room must reverse speed order");
        require(BattleMatchupPolicy.actsFirst(1, 100, 0, 20, true), "Trick Room must preserve priority order");

        double reliable = BattleMatchupPolicy.attackScore(1.1, 1, true, true, 0, 0, false, false);
        double inaccurate = BattleMatchupPolicy.attackScore(1.5, 0.5, true, false, 0, 0, false, false);
        require(reliable > inaccurate, "Reliable first KOs must beat stronger inaccurate attacks");
        require(BattleMatchupPolicy.attackScore(0.7, 1, false, false, 0, 0.3, false, true)
                        < BattleMatchupPolicy.attackScore(0.5, 1, false, false, 0, 0, false, false),
                "Suicidal recoil must lose to a viable attack");
        require(BattleMatchupPolicy.healingScore(20, 100, 50, 30, 0) > 1,
                "Healing that prevents a KO must have meaningful value");
        require(BattleMatchupPolicy.healingScore(100, 100, 50, 10, 0) == 0,
                "Full-health healing must not cause a loop");
        require(BattleMatchupPolicy.healingScore(20, 100, 30, 60, 0) < 0,
                "Insufficient healing must not be recommended as safe");

        var trainer = List.of("seismictoss", "toxic", "wish", "protect");
        var precise = BattleMatchupPolicy.resolveMoves(List.of("toxic"), trainer, List.of("flamethrower"), List.of("icebeam"));
        require(precise.complete() && precise.names().equals(trainer),
                "Exact trainer sets with status moves must not gain speculative coverage");
        require(BattleMatchupPolicy.resolveMoves(trainer, List.of("tackle"), List.of(), List.of("icebeam")).names().equals(trainer),
                "Four revealed moves must override trainer assumptions");
        require(!BattleMatchupPolicy.resolveMoves(List.of("icebeam"), trainer, List.of(), List.of()).complete(),
                "Contradicting revealed moves must invalidate a stale trainer set");
        require(!BattleMatchupPolicy.resolveMoves(List.of(), List.of("a", "b", "c", "d", "e"), List.of(), List.of()).complete(),
                "Merged trainer variants must be labeled uncertain");

        BattleFieldState field = new BattleFieldState();
        for (int i = 0; i < 4; i++) field.observe("cobblemon.battle.sidestart.ally.spikes");
        field.observe("cobblemon.battle.sidestart.ally.stealthrock");
        require(field.ally.spikes == 3 && field.ally.entryFraction(4, true, false, false) == 0.75,
                "Layer caps and four-times Rock weakness must affect entry damage");
        require(field.ally.entryFraction(4, true, true, false) == 0, "Boots must block entry damage");
        require(field.ally.entryFraction(4, true, false, true) == 0, "Magic Guard must block entry damage");
        require(field.ally.entryFraction(1, false, false, false) == 0.125, "Airborne Pokemon avoid Spikes but take Rock damage");
        field.observe("cobblemon.battle.sideend.ally.spikes");
        require(field.ally.spikes == 0 && field.ally.rocks, "Hazard removal must only clear its own effect");
        field.observe("cobblemon.battle.activate.courtchange");
        require(!field.ally.rocks && field.opponent.rocks, "Court Change must swap side conditions");
        field.observe("cobblemon.battle.fieldstart.trickroom");
        require(field.trickRoom, "Trick Room start must be tracked");
        field.observe("cobblemon.battle.fieldend.trickroom");
        require(!field.trickRoom, "Trick Room end must be tracked");
        field.observe("cobblemon.battle.sidestart.ally.tailwind");
        require(field.ally.tailwind && !field.opponent.tailwind, "Tailwind must belong to its actual side");
        require(new BattleFieldState().ally.entryFraction(4, true, false, false) == 0, "A new battle must start without hazards");
        System.out.println("Battle matchup, survival, turn order, moveset, move utility and field regression checks passed.");
    }

    private static int score(double outgoing, double incoming) {
        return BattleMatchupPolicy.offensiveScore(outgoing) - BattleMatchupPolicy.threatScore(incoming);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
