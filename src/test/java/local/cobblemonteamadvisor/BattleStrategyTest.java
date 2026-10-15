package local.cobblemonteamadvisor;

import java.util.List;
import java.util.Set;
import java.util.UUID;

final class BattleStrategyTest {
    static void run() {
        UUID own = UUID.randomUUID(), bench = UUID.randomUUID(), foe = UUID.randomUUID();
        Object request = new Object(), next = new Object();
        var roster = TrainerRoster.match("Camper Riley", Set.of("pinsir:49"), "pinsir:49", List.of("stormthrow"));
        check(roster.size() == 2 && roster.getFirst().ivs().get("hp") == 5,
                "A matched trainer must supply the complete lineup and bundled stats");
        check(TrainerRoster.match("Camper Riley", Set.of("pinsir:49"), "pinsir:49", List.of("icebeam")).isEmpty(),
                "Contradicting revealed moves must invalidate a bundled roster");
        check(TrainerRoster.match("Camper Riley", Set.of("pinsir:49", "charizard:49"), "pinsir:49", List.of()).isEmpty(),
                "A contradicted lineup must not preserve counters for a guessed team");
        BattleActionGuard guard = new BattleActionGuard();
        check(!guard.canSwitch(request, own, own, Set.of(), false, false, false), "Stale tile flags must not permit a self-switch");
        check(!guard.canSwitch(request, bench, own, Set.of(bench), false, false, false), "Any active allied slot must be excluded");
        check(!guard.canSwitch(request, bench, own, Set.of(own), false, false, true), "A destination reserved by another slot must be excluded");
        check(!guard.canSwitch(request, bench, own, Set.of(own), false, true, false), "Trapping must prevent voluntary switching");
        guard.reject(request, bench);
        check(!guard.canSwitch(request, bench, own, Set.of(own), false, false, false), "A rejected destination must not be retried for the same request");
        check(guard.canSwitch(next, bench, own, Set.of(own), false, false, false), "Rejection must not permanently blacklist a Pokemon");

        BattleObservations data = new BattleObservations();
        var key = new BattleObservations.DamageKey(foe, own, "earthquake", "neutral");
        data.damage(key, 0.2, 0.3, true, false);
        check(Math.abs(data.damageFactor(key) - 1.5) < 0.001, "Actual damage must calibrate the estimate");
        data.damage(key, 0.2, 0.8, false, false);
        data.damage(key, 0.2, 0.1, true, true);
        check(Math.abs(data.damageFactor(key) - 1.5) < 0.001, "Crits and censored KO damage must not corrupt the estimate");
        check(data.damageFactor(new BattleObservations.DamageKey(foe, own, "earthquake", "reflect")) == 1,
                "Learning must not leak across changed battle conditions");
        data.order(foe, own, "normal", 1, 0, true);
        data.order(foe, own, "normal", 1, 0, true);
        check(data.first(foe, own, "normal") == null, "Priority must not be interpreted as speed");
        data.order(foe, own, "normal", 0, 0, true);
        check(data.first(foe, own, "normal") == null, "One observation must not establish a speed guarantee");
        data.order(foe, own, "normal", 0, 0, true);
        check(Boolean.TRUE.equals(data.first(foe, own, "normal")), "Repeated clean move order must refine turn-order estimates");
        check(data.first(foe, own, "boosted") == null, "Speed changes must invalidate old ordering");
        data.order(own, foe, "normal", 0, 0, true);
        check(data.first(foe, own, "normal") == null, "Conflicting order must not produce a false guarantee");

        check(BattleMatchupPolicy.counterCost(10, 0, 100, 20) > BattleMatchupPolicy.counterCost(10, 0, 100, 90),
                "Risking the sole remaining counter must carry a larger preservation cost");
        check(BattleMatchupPolicy.counterCost(10, 10, 100, 20) == 0,
                "An equivalent healthy counter must remove the preservation penalty");
        check(BattleMatchupPolicy.counterCost(-4, -10, 100, 20) == 0,
                "A poor future matchup must not be mislabeled an essential counter");

        BattleFieldState field = new BattleFieldState();
        field.observe("cobblemon.battle.weather.raindance.start");
        check(field.weatherDamage("water") == 1.5 && field.weatherDamage("fire") == 0.5, "Rain must modify both attack types");
        field.observe("cobblemon.battle.weather.sunnyday.start");
        field.observe("cobblemon.battle.weather.raindance.end");
        check(field.weather.equals("sunnyday"), "Ending old weather must not erase new weather");
        field.observe("cobblemon.battle.weather.sunnyday.end");
        check(field.weatherDamage("water") == 1, "Weather expiration must restore neutral damage");
        field.observe("cobblemon.battle.fieldstart.psychicterrain");
        check(field.terrainDamage("normal", "quickattack", false, true, 1) == 0, "Psychic Terrain must block priority against grounded targets");
        check(field.terrainDamage("normal", "quickattack", false, false, 1) == 1, "Airborne targets must not receive grounded terrain protection");
        field.observe("cobblemon.battle.fieldstart.grassyterrain");
        check(field.terrainDamage("ground", "earthquake", true, true, 0) == 0.5, "Grassy Terrain must weaken Earthquake against grounded targets");
        check(field.terrainDamage("grass", "energyball", true, false, 0) == 1.3, "Terrain boosts require a grounded attacker");
        field.observe("cobblemon.battle.sidestart.opponent.reflect");
        check(field.opponent.reflect && !field.ally.reflect, "Screens must belong to the correct side");
        field.observe("cobblemon.battle.sideend.opponent.reflect");
        check(!field.opponent.reflect, "Screen removal must be tracked");

        BattleMoveState state = new BattleMoveState();
        state.used(own, "tackle");
        state.effect(own, "encore", true, null);
        check(state.restricted(own, "recover", true, false) != null, "Encore must lock the observed move");
        state.effect(own, "encore", false, null);
        state.effect(own, "taunt", true, null);
        check(state.restricted(own, "recover", true, false) != null && state.restricted(own, "tackle", false, false) == null,
                "Taunt must block status moves while retaining damaging moves");
        state.effect(own, "taunt", false, null);
        state.effect(own, "disable", true, "tackle");
        check(state.restricted(own, "tackle", false, false) != null, "Disable must block its specific move");
        state.enter(own);
        check(state.restricted(own, "tackle", false, false) == null, "Switching must reset volatile restrictions");
        state.used(own, "tackle", true);
        check(state.restricted(own, "recover", true, true) != null, "Choice items must lock the first observed move");
        state.startTurn(1, List.of(own));
        state.effect(own, "protect", true, null);
        check(state.protectedNow(own), "Protect must apply during its active turn");
        state.startTurn(2, List.of(own));
        check(!state.protectedNow(own), "Protect must not persist into the next turn");

        var hit = BattlePlanner.Action.attack("hit", 40, 0);
        var reply = BattlePlanner.Action.attack("reply", 30, 0);
        var heal = new BattlePlanner.Action("recover", 0, 1, 0, 10,
                new BattlePlanner.Effect(50, 0, 0, 1, 1, 1, 0, "", false, false), false, false, false);
        var wounded = new BattlePlanner.Combatant(50, 100, 50, 0, 0, "", List.of(hit, heal));
        var enemy = new BattlePlanner.Combatant(70, 100, 100, 0, 0, "", List.of(reply));
        var healingPlan = BattlePlanner.evaluate(wounded, enemy, heal, false, false);
        var attackingPlan = BattlePlanner.evaluate(wounded, enemy, hit, false, false);
        check(healingPlan.value() > attackingPlan.value() && healingPlan.next().equals("hit") && healingPlan.survives(),
                "Heal then attack must beat attacking into a second-turn KO");
        var healthy = new BattlePlanner.Combatant(100, 100, 200, 0, 0, "", List.of(hit, heal));
        check(BattlePlanner.evaluate(healthy, enemy, hit, false, false).value()
                        > BattlePlanner.evaluate(healthy, enemy, heal, false, false).value(),
                "Two attacks must beat unnecessary healing when they win first");
        var knockout = BattlePlanner.Action.attack("ko", 100, 0);
        var candidate = new BattlePlanner.Combatant(80, 100, 200, 0, 0, "", List.of(knockout));
        var switchPlan = BattlePlanner.evaluate(candidate, enemy, knockout, true, false);
        check(switchPlan.survives() && switchPlan.hpRemaining() == 50,
                "Switching must take an enemy hit before attacking on the next turn");
        var protect = new BattlePlanner.Action("protect", 0, 1, 4, 10,
                new BattlePlanner.Effect(0, 0, 0, 1, 1, 1, 0, "", true, false), false, false, false);
        var defender = new BattlePlanner.Combatant(60, 100, 100, 0, 0, "", List.of(protect));
        var hitter = new BattlePlanner.Combatant(100, 100, 50, 20, 0, "psn", List.of(BattlePlanner.Action.attack("hit", 50, 0)));
        check(BattlePlanner.evaluate(defender, hitter, protect, false, false).hpRemaining() == 10,
                "Repeated Protect must not create an invulnerable forecast");
        var repeated = new BattlePlanner.Combatant(60, 100, 100, 0, 0, "", List.of(protect), "protect", "");
        check(!BattlePlanner.evaluate(repeated, hitter, protect, false, false).survives(),
                "Actual previous-turn Protect must affect the next decision, not only forecast follow-ups");
        var substitute = new BattlePlanner.Combatant(70, 100, 100, 0, 25, "", List.of(reply));
        var burst = new BattlePlanner.Combatant(100, 100, 200, 0, 0, "", List.of(BattlePlanner.Action.attack("burst", 100, 0)));
        check(BattlePlanner.evaluate(burst, substitute, burst.actions().getFirst(), false, false).hpRemaining() == 70,
                "Breaking Substitute must require another hit to KO its owner");
        check(BattleMatchupPolicy.survivalScore(20, 100, 0, 25, -10) == -60,
                "Leftovers or terrain healing must not rescue a lethal hit retroactively");
        var exhausted = new BattlePlanner.Action("opener", 20, 1, 0, 10, BattlePlanner.Effect.NONE, true, false, true);
        var fragile = new BattlePlanner.Combatant(50, 100, 200, 0, 0, "", List.of(exhausted));
        check(!BattlePlanner.evaluate(fragile, enemy, exhausted, false, false).survives(),
                "An unavailable second action must still take the enemy's next hit");
        var charge = new BattlePlanner.Action("solarbeam", 100, 1, 0, 10, BattlePlanner.Effect.NONE,
                false, false, false, false, false, true);
        var charging = new BattlePlanner.Combatant(100, 100, 200, 0, 0, "", List.of(charge));
        check(BattlePlanner.evaluate(charging, enemy, charge, false, false).hpRemaining() == 70,
                "A charging move must take a hit before dealing damage on the next turn");
        var rest = new BattlePlanner.Action("rest", 0, 1, 0, 10,
                new BattlePlanner.Effect(100, 0, 0, 1, 1, 1, 0, "", false, false, "slp"), false, false, false);
        var resting = new BattlePlanner.Combatant(50, 100, 50, 0, 0, "", List.of(rest, knockout));
        check(BattlePlanner.evaluate(resting, enemy, rest, false, false).value() < 100,
                "Rest must not forecast an immediate attack while asleep");
        var alreadyFainted = new BattlePlanner.Combatant(0, 100, 200, 0, 0, "", List.of(knockout));
        check(!BattlePlanner.evaluate(alreadyFainted, enemy, knockout, true, false).survives(),
                "A Pokemon KO'd by entry hazards must not attack or heal");
        System.out.println("Switch safety, observations, field effects, restrictions and two-turn planning checks passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
