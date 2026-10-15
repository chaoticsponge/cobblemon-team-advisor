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

        check(!BattleSwitchPolicy.emergency(false, false, false), "Two ineffective Pokemon must not bypass the progress lock indefinitely");
        check(BattleSwitchPolicy.emergency(false, false, true), "A replacement that can actually damage an immune target must be allowed");
        check(BattleSwitchPolicy.emergency(true, true, true), "Immediate danger must still allow escape");
        check(!BattleSwitchPolicy.worthwhile(0, 4, 10, 40, 0.02, 0.01),
                "A weak Sentret's tiny damage must not justify switching just because a bench type score is higher");
        check(!BattleSwitchPolicy.worthwhile(0, 4, 40, 10, 0.01, 0.02), "Reversing that harmless matchup must also stay in");
        check(BattleSwitchPolicy.worthwhile(0, 4, 0, 12, 0.5, 0.1), "A real defensive improvement must still justify proactive switching");
        check(BattleSwitchPolicy.worthwhile(20, 4, 0, 0, 0.1, 0.1), "A substantial actual planning gain must remain eligible");
        check(!BattleSwitchPolicy.worthwhile(-5, 0, 0, 30, 1.2, 0.1), "A type bonus must not override a materially worse switch plan");
        check(!BattleSwitchPolicy.worthwhile(0, 4, 10, 10, 0.5, 0.1), "A tie in the final matchup score must not switch");
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

        System.out.println("Confirmed-switch progress, weak-opponent stability and emergency-switch checks passed.");
    }

    private static int rank(double multiplier, double hp, double maxHp, double entry, double incoming, double residual, double damage) {
        return BattleMatchupPolicy.matchupScore(multiplier, hp, maxHp, entry, incoming, residual, damage);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
