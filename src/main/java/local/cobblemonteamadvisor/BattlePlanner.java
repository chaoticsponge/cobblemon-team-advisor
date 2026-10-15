package local.cobblemonteamadvisor;

import java.util.List;

/** A bounded two-turn search over known legal actions, using conservative opponent replies. */
final class BattlePlanner {
    record Effect(double heal, double drain, double recoil, double attack, double defence, double speed,
                  double residual, String status, boolean protect, boolean substitute, String selfStatus) {
        Effect(double heal, double drain, double recoil, double attack, double defence, double speed,
               double residual, String status, boolean protect, boolean substitute) {
            this(heal, drain, recoil, attack, defence, speed, residual, status, protect, substitute, "");
        }
        static final Effect NONE = new Effect(0, 0, 0, 1, 1, 1, 0, "", false, false);
    }
    record Action(String id, double damage, double accuracy, int priority, int pp, Effect effect,
                  boolean opener, boolean cooldown, boolean physical, boolean bypassSubstitute, boolean recharge, boolean charging,
                  boolean breaksProtect) {
        Action(String id, double damage, double accuracy, int priority, int pp, Effect effect,
               boolean opener, boolean cooldown, boolean physical, boolean bypassSubstitute, boolean recharge, boolean charging) {
            this(id, damage, accuracy, priority, pp, effect, opener, cooldown, physical, bypassSubstitute, recharge, charging, false);
        }
        Action(String id, double damage, double accuracy, int priority, int pp, Effect effect,
               boolean opener, boolean cooldown, boolean physical) {
            this(id, damage, accuracy, priority, pp, effect, opener, cooldown, physical, false, false, false);
        }
        Action withDamage(double amount) {
            return new Action(id, amount, accuracy, priority, pp, effect, opener, cooldown, physical,
                    bypassSubstitute, recharge, charging, breaksProtect);
        }
        static Action attack(String id, double damage, int priority) {
            return new Action(id, damage, 1, priority, 10, Effect.NONE, false, false, true);
        }
    }
    record Combatant(double hp, double maxHp, double speed, double residual, double substitute,
                     String status, List<Action> actions, String lastMove, String chargedMove) {
        Combatant(double hp, double maxHp, double speed, double residual, double substitute, String status, List<Action> actions) {
            this(hp, maxHp, speed, residual, substitute, status, actions, "", "");
        }
    }
    record Plan(double value, String next, boolean survives, double hpRemaining, boolean acts) { }
    private static final Action PASS = Action.attack("pass", 0, 0);

    private static final class State {
        double hp, substitute, attack = 1, defence = 1, speed, residual;
        String status, last = "", charged = "";
        boolean protectedTurn, acted;
        boolean newlyBurned;
        State(Combatant fighter) {
            hp = fighter.hp; substitute = fighter.substitute; speed = fighter.speed;
            residual = fighter.residual; status = fighter.status; last = fighter.lastMove; charged = fighter.chargedMove;
        }
        State(State source) {
            hp = source.hp; substitute = source.substitute; attack = source.attack; defence = source.defence;
            speed = source.speed; residual = source.residual; status = source.status; last = source.last; charged = source.charged; newlyBurned = source.newlyBurned; acted = source.acted;
        }
    }

    static Plan evaluate(Combatant own, Combatant foe, Action first, boolean switching, boolean trickRoom) {
        State initial = new State(own), opponent = new State(foe);
        if (terminal(initial, opponent)) return new Plan(value(initial, opponent, own, foe), "", initial.hp > 0, initial.hp, false);
        double worst = Double.POSITIVE_INFINITY;
        String followup = "";
        boolean survives = true;
        double hpRemaining = own.hp;
        boolean acts = true;
        for (Action reply : actions(foe.actions)) {
            State mine = new State(initial), enemy = new State(opponent);
            turn(mine, enemy, own, foe, switching ? PASS : first, reply, trickRoom);
            Plan continuation = terminal(mine, enemy) ? new Plan(value(mine, enemy, own, foe), "", mine.hp > 0, mine.hp, mine.acted)
                    : bestSecond(mine, enemy, own, foe, first, reply, switching, trickRoom);
            acts &= continuation.acts;
            survives &= continuation.survives;
            if (continuation.value < worst) {
                worst = continuation.value; followup = continuation.next; hpRemaining = continuation.hpRemaining;
            }
        }
        return new Plan(worst, followup, survives, hpRemaining, acts);
    }

    private static Plan bestSecond(State mine, State enemy, Combatant own, Combatant foe,
                                   Action previous, Action reply, boolean switched, boolean trickRoom) {
        double best = -Double.MAX_VALUE;
        String next = "";
        boolean survives = false;
        double bestHp = mine.hp;
        boolean bestActs = false;
        for (Action action : actions(own.actions)) {
            if (!secondAllowed(action, previous, switched)) continue;
            double worst = Double.POSITIVE_INFINITY;
            boolean alive = true;
            double worstHp = mine.hp;
            boolean acts = true;
            for (Action answer : actions(foe.actions)) {
                if (!secondAllowed(answer, reply, false)) continue;
                State a = new State(mine), b = new State(enemy);
                turn(a, b, own, foe, action, answer, trickRoom);
                acts &= a.acted;
                alive &= a.hp > 0;
                double score = value(a, b, own, foe);
                if (score < worst) { worst = score; worstHp = a.hp; }
            }
            if (worst == Double.POSITIVE_INFINITY) {
                State a = new State(mine), b = new State(enemy);
                turn(a, b, own, foe, action, PASS, trickRoom);
                acts = a.acted;
                worst = value(a, b, own, foe); alive = a.hp > 0; worstHp = a.hp;
            }
            if (worst > best || worst == best && acts && !bestActs) { best = worst; next = action.id; survives = alive; bestHp = worstHp; bestActs = acts; }
        }
        if (best != -Double.MAX_VALUE) return new Plan(best, next, survives, bestHp, bestActs);
        // An exhausted or opener-only moveset must still suffer the opponent's next action.
        double worst = Double.POSITIVE_INFINITY;
        double remaining = mine.hp;
        for (Action answer : actions(foe.actions)) {
            State a = new State(mine), b = new State(enemy);
            turn(a, b, own, foe, PASS, secondAllowed(answer, reply, false) ? answer : PASS, trickRoom);
            if (value(a, b, own, foe) < worst) { worst = value(a, b, own, foe); remaining = a.hp; }
        }
        return new Plan(worst - 10, "", remaining > 0, remaining, mine.acted);
    }

    private static boolean secondAllowed(Action move, Action previous, boolean switched) {
        if (!switched && previous.recharge) return false;
        if (!switched && previous.charging) return move.id.equals(previous.id);
        return (switched || !move.opener) && (!move.id.equals(previous.id)
                || !move.cooldown && move.pp > 1 && !move.effect.substitute);
    }

    private static List<Action> actions(List<Action> actions) { return actions.isEmpty() ? List.of(PASS) : actions; }
    private static boolean terminal(State a, State b) { return a.hp <= 0 || b.hp <= 0; }

    private static void turn(State a, State b, Combatant own, Combatant foe, Action move, Action reply, boolean trickRoom) {
        boolean first = BattleMatchupPolicy.actsFirst(move.priority, a.speed, reply.priority, b.speed, trickRoom);
        if (first) {
            act(a, b, own, foe, move);
            if (!terminal(a, b)) act(b, a, foe, own, reply);
        } else {
            act(b, a, foe, own, reply);
            if (!terminal(a, b)) act(a, b, own, foe, move);
        }
        if (a.hp > 0) a.hp = Math.min(own.maxHp, a.hp - a.residual);
        if (b.hp > 0) b.hp = Math.min(foe.maxHp, b.hp - b.residual);
        a.protectedTurn = false; b.protectedTurn = false;
    }

    private static void act(State user, State target, Combatant own, Combatant foe, Action action) {
        if (user.hp <= 0 || target.hp <= 0 || !action.id.startsWith("item:") && (user.status.equals("slp") || user.status.equals("frz"))) return;
        if (action.charging && !user.charged.equals(action.id)) { user.charged = action.id; user.last = action.id; return; }
        user.charged = "";
        if (!action.id.equals("pass") && !action.id.equals("switch") && action.accuracy > 0) user.acted = true;
        double chance = action.accuracy * (user.status.equals("par") ? 0.75 : 1);
        Effect effect = action.effect;
        user.hp = Math.min(own.maxHp, user.hp + effect.heal * chance);
        if (effect.protect) {
            // Consecutive protection is unreliable and must not create an invincible two-turn plan.
            user.protectedTurn = !user.last.equals(action.id);
        }
        if (effect.substitute && user.substitute <= 0 && user.hp > own.maxHp / 4) {
            user.hp -= own.maxHp / 4; user.substitute = own.maxHp / 4;
        }
        if (action.breaksProtect) target.protectedTurn = false;
        boolean hadSubstitute = target.substitute > 0 && !action.bypassSubstitute;
        double onHit = target.protectedTurn ? 0 : action.damage * user.attack / target.defence
                * (user.newlyBurned && action.physical ? 0.5 : 1);
        // Excess damage only happens on a hit; a 50%-accurate overkill must not become a certain KO.
        double damage = Math.min(hadSubstitute ? target.substitute : target.hp, onHit) * chance;
        double dealt = Math.min(Math.max(0, target.hp), damage);
        if (hadSubstitute) {
            target.substitute = Math.max(0, target.substitute - damage);
            dealt = 0; // A broken substitute does not pass excess damage to its owner.
        } else {
            target.hp -= damage;
        }
        user.hp = Math.min(own.maxHp, user.hp + dealt * effect.drain) - dealt * effect.recoil;
        if (!target.protectedTurn && !hadSubstitute && target.status.isEmpty() && chance >= 0.7) {
            target.residual += effect.residual;
            target.status = effect.status;
            if (effect.status.equals("brn")) target.newlyBurned = true;
            if (effect.status.equals("par")) target.speed *= 0.5;
        }
        if (!effect.selfStatus.isEmpty()) user.status = effect.selfStatus;
        user.attack *= effect.attack; user.defence *= effect.defence; user.speed *= effect.speed;
        user.last = action.id;
    }

    private static double value(State own, State foe, Combatant a, Combatant b) {
        if (own.hp <= 0 && foe.hp <= 0) return -10;
        if (own.hp <= 0) return -100 - Math.max(0, foe.hp) / b.maxHp * 20;
        if (foe.hp <= 0) return 100 + own.hp / a.maxHp * 20;
        return 35 * (1 - foe.hp / b.maxHp) + 25 * own.hp / a.maxHp;
    }
}
