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
        System.out.println("Confirmed-switch progress, weak-opponent stability and emergency-switch checks passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
