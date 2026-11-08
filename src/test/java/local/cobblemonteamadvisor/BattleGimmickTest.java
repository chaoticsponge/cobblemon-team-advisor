package local.cobblemonteamadvisor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class BattleGimmickTest {
    static void run() {
        check(BattleGimmickPolicy.type(true, "normal", "pixilate").equals("normal"), "Pixilate must not convert a Normal Z-Move");
        check(BattleGimmickPolicy.type(false, "normal", "pixilate").equals("fairy"), "Ordinary Normal attacks must retain Pixilate conversion");
        check(BattleGimmickPolicy.prefer("mega", -100, "", 2000), "A legal Mega move must take priority even when the ordinary hit scores higher");
        check(!BattleGimmickPolicy.prefer("zmove", 3000, "mega", -100), "A stronger Z-Move must not postpone an available Mega");
        check(BattleGimmickPolicy.prefer("mega", 100, "mega", 50), "Choose the best legal move within the Mega options");
        check(!BattleGimmickPolicy.prefer("mega", Double.NEGATIVE_INFINITY, "", 50), "A locally restricted Mega move must remain unavailable");
        check(BattleGimmickPolicy.prefer("zmove", 100, "", 50), "Without Mega, ordinary score-based gimmick selection must still apply");
        check(!BattleGimmickPolicy.immediate("max"), "Immediate Mega policy must not force other once-per-battle mechanics");
        var policy = new BattleGimmickPolicy();
        policy.submitted("mega"); policy.rejected();
        check(!policy.allowed("mega"), "A rejected transformation must not be retried every tick");
        check(policy.allowed("zmove"), "A rejected Mega must not disable another available mechanic");
        policy.submitted("max"); policy.answered(); policy.rejected();
        check(policy.allowed("max"), "An acknowledged action must not be blamed for a later invalid choice");
        policy.submitted("max:flamethrower"); policy.rejected();
        check(!policy.allowed("max:flamethrower") && policy.allowed("max:airslash"), "An invalid active Max move must leave other Max moves usable");
        check(new BattleGimmickPolicy().allowed("mega"), "Rejections must reset between battles");
        check(BattleGimmickPolicy.stab(true, true, false, false) == 2, "Matching original Tera type gets 2x STAB");
        check(BattleGimmickPolicy.stab(true, false, false, false) == 1.5, "Tera retains original-type STAB");
        check(BattleGimmickPolicy.stab(false, true, false, false) == 1.5, "A new Tera type gets STAB");
        check(BattleGimmickPolicy.stab(true, false, false, true) == 1.5, "Adaptability does not boost a retained type that is no longer the defensive type");
        check(BattleGimmickPolicy.stab(false, false, false, false) == 1, "Unrelated moves gain no Tera bonus");
        check(BattleGimmickPolicy.stab(true, true, false, true) == 2.25, "Adaptability Tera STAB must not be doubled twice");
        check(BattleGimmickPolicy.stab(true, false, true, false) == 2, "First original-type Stellar attack gets 2x STAB");
        try (var input = BattleGimmickTest.class.getResourceAsStream("/battle-gimmick-moves.json")) {
            JsonObject powers = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            check(powers.size() > 500, "Powered-move metadata must cover the move pool");
            power(powers, "thunderbolt", 175, 130);
            power(powers, "closecombat", 190, 95);
            power(powers, "brine", 120, 120); // Never double a converted Z/Max move below half HP.
            power(powers, "bulletseed", 140, 130);
            power(powers, "flyingpress", 170, 90); // Explicit Showdown override, not the generic power ladder.
            power(powers, "weatherball", 160, 130);
            check(BattleGimmickPolicy.power("max", 160, 200, 150) == 160, "Fixed-power G-Max moves retain their own power");
            check(BattleGimmickPolicy.power("zmove", 210, 175, 130) == 210, "Signature Z-Moves retain their own power");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        double base = BattleMatchupPolicy.moveValue(10, 1, 0.6, 1, 0, true, false);
        double z = BattleMatchupPolicy.moveValue(10, 1, 1.2, 1, 1, true, false);
        check(z > base, "A reliable powered KO must beat an ordinary nonlethal hit");
        System.out.println("Gimmick rejection, Tera STAB, Z/Max power, signature exceptions and KO ranking checks passed.");
    }

    private static void power(JsonObject powers, String move, int z, int max) {
        var row = powers.getAsJsonArray(move);
        check(BattleGimmickPolicy.power("zmove", 1, row.get(0).getAsInt(), row.get(1).getAsInt()) == z, move + " Z power");
        check(BattleGimmickPolicy.power("max", 1, row.get(0).getAsInt(), row.get(1).getAsInt()) == max, move + " Max power");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
