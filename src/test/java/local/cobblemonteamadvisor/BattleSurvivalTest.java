package local.cobblemonteamadvisor;

import java.util.List;
import java.util.UUID;

final class BattleSurvivalTest {
    static void run() {
        var hit = BattlePlanner.Action.attack("hit", 30, 0);
        var boost = status("swordsdance", new BattlePlanner.Effect(0, 0, 0, 2, 1, 1, 0, "", false, false, "", 1, 1), 0);
        var frail = fighter(40, 200, List.of(hit, boost));
        var lethal = fighter(100, 100, List.of(BattlePlanner.Action.attack("reply", 60, 0)));
        var setup = BattlePlanner.evaluate(frail, lethal, boost, false, false);
        var attack = BattlePlanner.evaluate(frail, lethal, hit, false, false);
        check(setup.acts() && !setup.followsUp(), "Performing a setup is not proof of a usable follow-up");
        double unsafe = BattleMatchupPolicy.utilityValue(setup.value(), 1, setup.survives(), true, setup.followsUp() && setup.survives(), 0.4);
        double chip = BattleMatchupPolicy.moveValue(attack.value(), 0, 0.3, 1, 0, true, false) + BattleMatchupPolicy.survivalPenalty(attack.survives());
        check(chip > unsafe, "Clair-style lethal setups must lose to damage before fainting");
        var slower = fighter(60, 100, List.of(hit, boost));
        var faster = fighter(100, 200, List.of(BattlePlanner.Action.attack("reply", 40, 0)));
        var tooLate = BattlePlanner.evaluate(slower, faster, boost, false, false);
        check(tooLate.acts() && !tooLate.followsUp(), "Surviving the setup turn but dying before the next attack is still futile");
        var dance = status("dragondance", new BattlePlanner.Effect(0, 0, 0, 2, 1, 2, 0, "", false, false, "", 1, 1), 0);
        var strongerHit = BattlePlanner.Action.attack("hit", 40, 0);
        var dancer = fighter(80, 80, List.of(strongerHit, dance));
        var rival = fighter(70, 100, List.of(BattlePlanner.Action.attack("hit", 50, 0)));
        var useful = BattlePlanner.evaluate(dancer, rival, dance, false, false);
        check(useful.followsUp() && useful.survives() && useful.next().equals("hit"), "A speed-and-damage boost that wins next turn must remain usable");
        check(BattleMatchupPolicy.utilityValue(100, 1, true, true, true, 0.2)
                < BattleMatchupPolicy.utilityValue(100, 1, true, true, true, 0.8), "Lower starting HP must reduce setup preference");
        check(BattleMatchupPolicy.utilityValue(100, 0.4, true, false, false, 0.2)
                == BattleMatchupPolicy.utilityValue(100, 0.4, true, false, false, 0.8), "Protect must be exempt from the low-HP setup penalty");
        var protect = status("protect", new BattlePlanner.Effect(0, 0, 0, 1, 1, 1, 0, "", true, false), 4);
        var poison = new BattlePlanner.Combatant(20, 100, 200, 25, 0, "psn", List.of(BattlePlanner.Action.attack("hit", 60, 0)));
        check(BattlePlanner.evaluate(fighter(20, 100, List.of(protect)), poison, protect, false, false).survives(),
                "Low-HP Protect must retain a line that wins through residual damage");
        check(BattleMatchupPolicy.boostChange(2, "simple") == 4, "Simple must double modeled stat changes");
        check(BattleMatchupPolicy.boostChange(-2, "contrary") == 2 && BattleMatchupPolicy.boostChange(2, "contrary") == -2,
                "Contrary must invert setup and self-inflicted drops");
        categories(); recharge(protect); restrictions();
        System.out.println("Lethal setup rejection, productive follow-ups, low-HP Protect, category-correct boosts, recharge windows and Destiny Bond cooldown checks passed.");
    }

    private static void categories() {
        var hit = BattlePlanner.Action.attack("hit", 70, 0);
        var iron = status("irondefense", new BattlePlanner.Effect(0, 0, 0, 1, 2, 1, 0, "", false, false, "", 1, 1), 0);
        var own = fighter(70, 200, List.of(hit, iron));
        var physical = fighter(60, 100, List.of(BattlePlanner.Action.attack("physical", 80, 0)));
        var special = fighter(60, 100, List.of(new BattlePlanner.Action("thunderbolt", 80, 1, 0, 10, BattlePlanner.Effect.NONE, false, false, false)));
        check(BattlePlanner.evaluate(own, physical, iron, false, false).followsUp(), "Iron Defense can protect a physical follow-up");
        check(!BattlePlanner.evaluate(own, special, iron, false, false).followsUp(), "Iron Defense must not falsely defend against Dialga's Thunderbolt");
        var plot = status("nastyplot", new BattlePlanner.Effect(0, 0, 0, 1, 1, 1, 0, "", false, false, "", 2, 1), 0);
        var foul = BattlePlanner.Action.attack("foulplay", 20, 0);
        var night = new BattlePlanner.Action("nightdaze", 20, 1, 0, 10, BattlePlanner.Effect.NONE, false, false, false);
        var mixed = fighter(100, 200, List.of(foul, night));
        check(BattlePlanner.evaluate(mixed, fighter(100, 100, List.of()), plot, false, false).next().equals("nightdaze"),
                "Nasty Plot must improve the special attack rather than the physical one");
        var smash = status("shellsmash", new BattlePlanner.Effect(0, 0, 0, 2, 2.0 / 3, 2, 0, "", false, false, "", 2, 2.0 / 3), 0);
        check(!BattlePlanner.evaluate(fighter(70, 200, List.of(hit)), fighter(100, 100,
                List.of(new BattlePlanner.Action("special", 60, 1, 0, 10, BattlePlanner.Effect.NONE, false, false, false))), smash, false, false).followsUp(),
                "Setup defense drops must be included in survival estimates");
    }

    private static void recharge(BattlePlanner.Action protect) {
        var hit = BattlePlanner.Action.attack("hit", 60, 0);
        var heal = status("roost", new BattlePlanner.Effect(70, 0, 0, 1, 1, 1, 0, "", false, false), 0);
        var burst = new BattlePlanner.Action("roaroftime", 90, 1, 0, 10, BattlePlanner.Effect.NONE, false, false, false, false, true, false);
        var foe = new BattlePlanner.Combatant(50, 100, 200, 0, 0, "", List.of(burst), "roaroftime", "", true);
        var rescue = BattlePlanner.evaluate(fighter(35, 100, List.of(hit, heal)), foe, heal, false, false);
        check(rescue.survives() && rescue.followsUp() && rescue.next().equals("hit"), "Observed recharge must allow healing followed by an attack");
        var smallHeal = status("recover", new BattlePlanner.Effect(50, 0, 0, 1, 1, 1, 0, "", false, false), 0);
        check(!BattlePlanner.evaluate(fighter(35, 100, List.of(hit, smallHeal)), foe, smallHeal, false, false).survives(),
                "The foe must resume attacking after its one recharge turn");
        var healthyFoe = fighter(100, 100, List.of(burst));
        check(!BattlePlanner.evaluate(fighter(35, 200, List.of(protect)), healthyFoe, protect, false, false).survives(),
                "A beam blocked by Protect must not grant a fictitious recharge turn");
        var counter = fighter(100, 100, List.of(BattlePlanner.Action.attack("reply", 10, 0)));
        var usedBeam = BattlePlanner.evaluate(fighter(100, 200, List.of(burst)), counter, burst, false, false);
        check(usedBeam.hpRemaining() == 80 && !usedBeam.followsUp(), "A successful beam must spend the next turn recharging");
    }

    private static void restrictions() {
        var state = new BattleMoveState(); var id = UUID.randomUUID();
        state.startTurn(1, List.of(id)); state.used(id, "roaroftime");
        check(!state.recharging(id), "A move name alone must not invent a confirmed recharge");
        state.recharge(id); state.startTurn(2, List.of(id));
        check(state.recharging(id), "The explicit recharge message applies to the next turn");
        state.consumedRecharge(id); check(!state.recharging(id), "A consumed recharge must clear");
        state.recharge(id); state.startTurn(4, List.of(id)); check(!state.recharging(id), "Unobserved recharge flags must not persist indefinitely");
        state.used(id, "destinybond");
        check(state.unavailable(id, "destinybond", List.of("destinybond", "shadowball"), true) != null, "Consecutive Destiny Bond must be suppressed");
        state.used(id, "shadowball"); check(state.unavailable(id, "destinybond", List.of("destinybond", "shadowball"), true) == null, "Another move resets the Destiny Bond cooldown");
        state.recharge(id); state.enter(id); check(!state.recharging(id), "Switching out must clear recharge history");
    }

    private static BattlePlanner.Combatant fighter(double hp, double speed, List<BattlePlanner.Action> moves) {
        return new BattlePlanner.Combatant(hp, 100, speed, 0, 0, "", moves);
    }
    private static BattlePlanner.Action status(String id, BattlePlanner.Effect effect, int priority) {
        return new BattlePlanner.Action(id, 0, 1, priority, 10, effect, false, false, false);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
