package local.cobblemonteamadvisor;

import java.util.HashSet;
import java.util.Set;

/** Pure rules shared by live gimmick scoring and regression checks. */
final class BattleGimmickPolicy {
    private final Set<String> rejected = new HashSet<>();
    private String pending = "";

    boolean allowed(String gimmick) { return !rejected.contains(gimmick); }
    void submitted(String gimmick) { pending = gimmick; }
    void rejected() { if (!pending.isEmpty()) rejected.add(pending); pending = ""; }
    void answered() { pending = ""; }

    static boolean powered(String gimmick) { return gimmick.equals("zmove") || gimmick.equals("max"); }

    static double stab(boolean originalType, boolean teraType, boolean stellar, boolean adaptability) {
        if (stellar) return originalType ? adaptability ? 2.25 : 2 : 1.2;
        if (teraType) return originalType ? adaptability ? 2.25 : 2 : adaptability ? 2 : 1.5;
        return originalType ? 1.5 : 1;
    }

    static String type(boolean powered, String original, String ability) {
        return powered ? original : BattleDamageModifiers.type(original, ability);
    }

    static double power(String gimmick, double advertised, int zPower, int maxPower) {
        return advertised > 1 ? advertised : gimmick.equals("zmove") ? zPower : gimmick.equals("max") ? maxPower : advertised;
    }
}
