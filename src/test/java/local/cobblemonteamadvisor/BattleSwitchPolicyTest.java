package local.cobblemonteamadvisor;

import java.util.UUID;

final class BattleSwitchPolicyTest {
    static void run() {
        UUID golurk = UUID.randomUUID(), blackfog = UUID.randomUUID(), sentret = UUID.randomUUID(), otherFoe = UUID.randomUUID();
        var policy = new BattleSwitchPolicy();
        check(policy.allows(golurk, sentret, false), "Initial switching must remain available");
        policy.submitted(blackfog, sentret, false);
        check(policy.allows(blackfog, sentret, false), "An unaccepted switch must not create a cooldown");
        policy.entered(blackfog);
        check(!policy.allows(blackfog, sentret, false), "After Golurk switches to Blackfog, Blackfog must act before switching back");
        policy.acted(sentret);
        check(!policy.allows(blackfog, sentret, false), "The opponent's action must not unlock another player switch");
        check(policy.allows(blackfog, sentret, true), "A threatened or completely ineffective active Pokemon must still be able to escape");
        check(policy.allows(blackfog, otherFoe, false), "A changed opponent must allow a new counter-switch");
        policy.acted(blackfog);
        check(policy.allows(blackfog, sentret, false), "Once the incoming Pokemon acts, normal switching decisions may resume");
        policy.submitted(golurk, sentret, false);
        policy.entered(golurk);
        check(!policy.allows(golurk, sentret, false), "The same protection must work in the reverse direction");
        policy.submitted(blackfog, sentret, false);
        policy.rejected();
        policy.entered(blackfog);
        check(policy.allows(blackfog, sentret, false), "Rejected choices must not create a confirmed-switch lock");
        policy.submitted(blackfog, sentret, false);
        policy.submitted(blackfog, sentret, true);
        policy.entered(blackfog);
        check(policy.allows(blackfog, sentret, false), "Forced replacement must not inherit a voluntary-switch lock");
        check(!policy.allows(golurk, sentret, false), "Another slot entering must not unlock Golurk's pending action");
        check(new BattleSwitchPolicy().allows(golurk, sentret, false), "Switch history must not carry into a new battle");

        check(!BattleSwitchPolicy.emergency(false, false, false, true, 0, 0), "Two ineffective Pokemon must not bypass the progress lock");
        check(BattleSwitchPolicy.emergency(false, false, true, true, 0, 0), "A safe damaging replacement can escape an ineffective matchup");
        check(BattleSwitchPolicy.emergency(true, true, true, true, 1.5, 0.5), "A safer replacement that survives its follow-up can escape");
        check(!BattleSwitchPolicy.emergency(true, true, true, false, 1.5, 0.6), "Surviving entry without surviving the follow-up cannot unlock another swap");
        check(!BattleSwitchPolicy.emergency(true, true, true, true, 1.1, 0.9), "A marginal safety change cannot unlock a switch chain");
        check(!BattleSwitchPolicy.worthwhile(0, 4), "A defensive type-score improvement alone must not switch");
        check(!BattleSwitchPolicy.worthwhile(-2, 0), "The old slightly-worse-plan fallback must be removed");
        check(BattleSwitchPolicy.worthwhile(20, 4), "A substantial actual planning gain remains eligible");
        check(!BattleSwitchPolicy.worthwhile(9, 4), "Switching must earn its full improvement margin");
        check(BattleSwitchPolicy.worthwhile(10, 4), "A clear productive improvement remains available");
        check(!BattleSwitchPolicy.worthwhile(Double.NaN, 4), "Invalid forecasts must never submit switches");
        check(BattleSwitchPolicy.healthAdjustment(0.4, 1) == -15, "A fresh bench member cannot manufacture a party-health gain");
        check(BattleSwitchPolicy.healthAdjustment(1, 1) == 0, "Equal initial health needs no baseline adjustment");
        check(!BattleSwitchPolicy.viable(true, false, true), "Surviving entry is insufficient without an action");
        check(!BattleSwitchPolicy.viable(true, true, false), "Executing a useless action is insufficient");
        check(!BattleSwitchPolicy.viable(false, true, true), "Entry-hazard or switch-in KOs are never viable");
        check(BattleSwitchPolicy.viable(true, true, true), "A productive counter remains viable");
        check(!policy.allows(golurk, sentret, BattleSwitchPolicy.emergency(true, true, true, false, 1.5, 0.6)),
                "Repeated Bertha-style danger must not bypass the existing action lock");

        check(rank(0, 20, 100, 0, 0, 0, 0.3) > rank(0.5, 100, 100, 0, 5, 0, 2),
                "A surviving immune counter must rank above a resistant counter even when the latter has more offence and HP");
        check(rank(0.5, 50, 100, 0, 5, 0, 0.2) > rank(1, 100, 100, 0, 10, 0, 2),
                "Resistance must outrank neutral defence with a stronger offensive multiplier when both are viable");
        check(rank(0, 5, 100, 0, 0, 10, 2) < rank(0.5, 100, 100, 0, 10, 0, 0.5),
                "An immune Pokemon that faints to residual damage must lose to a surviving resistant counter");
        check(rank(0, 10, 100, 15, 0, 0, 2) < rank(1, 100, 100, 0, 10, 0, 0.5),
                "An entry-hazard KO must invalidate the apparent immunity advantage");
        check(rank(0.5, 100, 100, 0, 10, 0, 0.5) > rank(0.5, 30, 100, 0, 10, 0, 0.5),
                "Current health must choose between otherwise comparable resistant counters");
        check(rank(0.5, 100, 100, 0, 10, 0, 0.5) > rank(0.5, 100, 100, 30, 10, 0, 0.5),
                "Health lost entering must lower a counter's score");
        check(rank(0.5, 80, 100, 0, 10, 0, 0.5) > rank(0.5, 80, 100, 0, 40, 0, 0.5),
                "Health left after the incoming hit must affect score");
        check(rank(0.5, 120, 200, 0, 30, 0, 0.5) > rank(0.5, 60, 100, 0, 30, 0, 0.5),
                "Actual remaining HP and hit survival must matter as well as the percentage bar");
        check(rank(0, 100, 100, 0, 0, 0, 0) < rank(1, 100, 100, 0, 10, 0, 0.5),
                "A Pokemon that cannot hurt the opponent is not a viable offensive counter");

        var hit = BattlePlanner.Action.attack("hit", 50, 0);
        var reply = BattlePlanner.Action.attack("reply", 60, 0);
        var enemy = new BattlePlanner.Combatant(100, 100, 100, 0, 0, "", java.util.List.of(reply));
        var slow = new BattlePlanner.Combatant(100, 100, 50, 0, 0, "", java.util.List.of(hit));
        var slowPlan = BattlePlanner.evaluate(slow, enemy, hit, true, false);
        check(!slowPlan.acts() && !slowPlan.survives(),
                "Bertha regression: entering at 100 HP and taking 60 twice before attacking is not productive");
        check(!BattleSwitchPolicy.viable(true, slowPlan.acts(), true), "Surviving that first hit must not make this a viable switch");
        var fast = new BattlePlanner.Combatant(100, 100, 200, 0, 0, "", java.util.List.of(hit));
        var fastPlan = BattlePlanner.evaluate(fast, enemy, hit, true, false);
        check(fastPlan.acts() && !fastPlan.survives(), "An attack before fainting must be distinguished from fainting without attacking");
        var ko = BattlePlanner.Action.attack("ko", 120, 0);
        var counter = new BattlePlanner.Combatant(100, 100, 200, 0, 0, "", java.util.List.of(ko));
        var counterPlan = BattlePlanner.evaluate(counter, enemy, ko, true, false);
        check(counterPlan.acts() && counterPlan.survives(), "A fast counter that KOs after entry must still permit proactive switching");
        var asleep = new BattlePlanner.Combatant(100, 100, 200, 0, 0, "slp", java.util.List.of(ko));
        check(!BattlePlanner.evaluate(asleep, enemy, ko, true, false).acts(), "Sleep must not promise a follow-up action");
        var priority = BattlePlanner.Action.attack("priority", 60, 1);
        var priorityFoe = new BattlePlanner.Combatant(100, 100, 100, 0, 0, "", java.util.List.of(priority));
        check(!BattlePlanner.evaluate(fast, priorityFoe, hit, true, false).acts(), "Priority must be included in post-entry action viability");
        var charger = new BattlePlanner.Action("charge", 100, 1, 0, 10, BattlePlanner.Effect.NONE, false, false, true, false, false, true);
        var charging = new BattlePlanner.Combatant(100, 100, 200, 0, 0, "", java.util.List.of(charger));
        check(!BattlePlanner.evaluate(charging, enemy, charger, true, false).acts(), "Starting a charging move must not count as delivering its damage");

        System.out.println("Confirmed-switch progress, weak-opponent stability and emergency-switch checks passed.");
    }

    private static int rank(double multiplier, double hp, double maxHp, double entry, double incoming, double residual, double damage) {
        return BattleMatchupPolicy.matchupScore(multiplier, hp, maxHp, entry, incoming, residual, damage);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
