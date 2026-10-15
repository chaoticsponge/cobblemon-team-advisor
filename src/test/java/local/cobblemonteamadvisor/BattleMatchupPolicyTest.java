package local.cobblemonteamadvisor;

import java.util.List;
import java.util.UUID;

public final class BattleMatchupPolicyTest {
    public static void main(String[] args) {
        double remaining = BattleMatchupPolicy.healthFraction(0.75, 120, false) * 120;
        require(remaining == 90, "Opponent 75% HP must remain 90/120, not be divided by max HP twice");
        require(!BattleMatchupPolicy.normalHitKo(26, remaining, true), "Feint dealing about 25% must not KO a 75%-healthy Vivillon");
        require(BattleMatchupPolicy.normalHitKo(130, remaining, true), "A stronger normal hit must retain its real finishing advantage");
        require(BattleMatchupPolicy.healthFraction(90, 120, true) == 0.75, "Allied flat HP must still be divided by its maximum");
        require(BattleMatchupPolicy.healthFraction(1, 120, false) == 1, "Full opponent HP must not look nearly fainted");
        require(BattleMatchupPolicy.healthFraction(0.75, 1, false) == 0.75, "Normalized-max public HP must also work");
        require(BattleMatchupPolicy.healthFraction(0, 120, false) == 0, "A fainted opponent must stay fainted");
        require(BattleMatchupPolicy.healthFraction(0, 120, true) == 0, "A fainted ally must stay fainted");
        require(BattleMatchupPolicy.healthFraction(Double.NaN, 120, false) == 1, "Invalid health must not create a false near-fainted target");
        double observed = BattleMatchupPolicy.healthFraction(1, 120, false) - BattleMatchupPolicy.healthFraction(0.75, 120, false);
        require(observed == 0.25, "Damage learning must record a quarter-health hit as 25%, not 0.2%");
        var hpObservations = new BattleObservations();
        var hpKey = new BattleObservations.DamageKey(UUID.randomUUID(), UUID.randomUUID(), "feint", "normal");
        hpObservations.damage(hpKey, 0.25, observed, true, false);
        require(hpObservations.damageFactor(hpKey) == 1, "Correctly predicted quarter-health hits must not distort damage calibration");
        require(BattleMatchupPolicy.normalHitKo(120, 100, true), "Overkill on the minimum normal roll must qualify as KO");
        require(BattleMatchupPolicy.normalHitKo(100, 100, true), "An exact minimum-damage KO must qualify");
        require(!BattleMatchupPolicy.normalHitKo(85, 100, true), "A high roll or random crit must not turn 85 minimum damage into a guaranteed KO");
        require(!BattleMatchupPolicy.normalHitKo(240, 100, false), "Hidden raid HP must never produce a species-HP KO label");
        require(!BattleMatchupPolicy.normalHitKo(120, 0, true), "A fainted opponent is not a new KO opportunity");
        double safeFinish = BattleMatchupPolicy.moveValue(70, 4, 1.2, 1, true, true, false);
        double rollFinish = BattleMatchupPolicy.moveValue(100, 1, 0.95, 1, false, true, false);
        require(safeFinish > rollFinish, "Trainer minimum-damage KOs must beat optimistic planner roll KOs");
        require(safeFinish > BattleMatchupPolicy.moveValue(70, 4, 2.4, 0.5, true, true, false),
                "A KO on hit with 50% accuracy must remain less reliable than a certain hit KO");
        require(BattleMatchupPolicy.moveValue(0, 0, 2.4, 0.5, false, true, true)
                        > BattleMatchupPolicy.moveValue(0, 0, 0.85, 1, false, true, true),
                "Unknown raid HP must preserve expected overkill damage instead of capping both attacks at a false KO");
        require(BattleMatchupPolicy.moveValue(0, 0, 1.2, 1, true, false, false) == 0,
                "A Pokemon that cannot act must not receive a finishing bonus");
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
        UUID sirfetchd = UUID.randomUUID();
        UUID opponent = UUID.randomUUID();
        var moves = List.of("firstimpression", "fakeout", "closecombat", "lastresort");
        BattleMoveState history = new BattleMoveState();
        history.startTurn(1, List.of(sirfetchd, opponent));
        require(history.unavailable(sirfetchd, "firstimpression", moves, true) == null, "First Impression must work on entry");
        history.startTurn(1, List.of(sirfetchd, opponent));
        require(history.unavailable(sirfetchd, "firstimpression", moves, true) == null,
                "Repeated rendering or rejected choices must not spend an opener");
        history.used(sirfetchd, "firstimpression");
        require(history.unavailable(sirfetchd, "firstimpression", moves, true) != null, "First Impression must not repeat");
        require(history.unavailable(sirfetchd, "fakeout", moves, true) != null, "Using another move must also consume Fake Out's opportunity");
        history.startTurn(2, List.of(sirfetchd, opponent));
        require(history.unavailable(opponent, "firstimpression", moves, true) != null,
                "A spent turn must invalidate openers even without a used-move message");
        history.enter(sirfetchd);
        require(history.unavailable(sirfetchd, "firstimpression", moves, true) == null, "A switch-in must reset opener eligibility");
        history.startTurn(3, List.of(sirfetchd, opponent));
        require(history.unavailable(sirfetchd, "firstimpression", moves, true) == null,
                "A mid-turn switch-in must retain its opener for the next decision turn");
        history.attempted(sirfetchd);
        require(history.unavailable(sirfetchd, "matblock", moves, true) != null,
                "Flinching or paralysis must also consume the first move opportunity");

        history.enter(sirfetchd);
        history.used(sirfetchd, "gigatonhammer");
        require(history.unavailable(sirfetchd, "gigatonhammer", moves, true) != null, "Gigaton Hammer cannot repeat consecutively");
        history.used(sirfetchd, "closecombat");
        require(history.unavailable(sirfetchd, "gigatonhammer", moves, true) == null, "Another move must end the cooldown");
        history.used(sirfetchd, "bloodmoon");
        require(history.unavailable(sirfetchd, "bloodmoon", moves, true) != null, "Blood Moon cannot repeat consecutively");
        history.enter(sirfetchd);
        require(history.unavailable(sirfetchd, "bloodmoon", moves, true) == null, "Switching must clear consecutive-move history");

        var resortSet = List.of("protect", "tackle", "lastresort");
        require(history.unavailable(sirfetchd, "lastresort", resortSet, true) != null, "Last Resort needs its prerequisites");
        history.used(sirfetchd, "protect");
        require(history.unavailable(sirfetchd, "lastresort", resortSet, true) != null, "Partially used movesets must not unlock Last Resort");
        history.used(sirfetchd, "tackle");
        require(history.unavailable(sirfetchd, "lastresort", resortSet, true) == null, "Using every other move unlocks Last Resort");
        require(history.unavailable(sirfetchd, "lastresort", List.of("lastresort"), true) != null,
                "Last Resort cannot be the only move");
        history.used(sirfetchd, "noretreat");
        require(history.unavailable(sirfetchd, "noretreat", moves, true) != null, "No Retreat must not be repeatedly selected");
        history.enter(sirfetchd);
        require(history.unavailable(sirfetchd, "lastresort", resortSet, true) != null, "Last Resort prerequisites reset on entry");
        require(new BattleMoveState().unavailable(sirfetchd, "firstimpression", moves, true) == null,
                "A new battle must not retain restrictions");
        BattleStrategyTest.run();
        PokedexGuideTest.run();
        System.out.println("Battle policy and conditional-move regression checks passed.");
    }

    private static int score(double outgoing, double incoming) {
        return BattleMatchupPolicy.offensiveScore(outgoing) - BattleMatchupPolicy.threatScore(incoming);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
