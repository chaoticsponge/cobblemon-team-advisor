package local.cobblemonteamadvisor;

import java.util.List;
import java.util.Random;

/** Bounded singles trials, not a calibrated prediction or the server's battle simulator. */
final class TrainerWinEstimator {
    static final int TRIALS = 128;
    record Fighter(double hp, double maxHp, double speed, double residual, String status, boolean choice) { }
    record Attack(BattlePlanner.Action move, double[] damage) {
        double damage(double hp, double maxHp, double targetHp, double targetMaxHp) {
            return damage[bucket(hp / maxHp) * 5 + bucket(targetHp / targetMaxHp)];
        }
    }
    record Model(List<Fighter> fighters, int allies, List<Attack>[][] attacks, int lead) { }
    record Estimate(int wins, int losses, int unresolved) {
        String label() {
            if (unresolved > TRIALS / 4) return "Est. win: uncertain";
            int low = percent(wins), high = percent(wins + unresolved);
            return "Est. win: ~" + low + (low == high ? "" : "–" + high) + "%";
        }
        private static int percent(int count) { return Math.clamp((int) Math.round(100.0 * count / TRIALS), 1, 99); }
    }

    static int bucket(double fraction) {
        return fraction >= 1 ? 0 : fraction > 0.5 ? 1 : fraction > 1.0 / 3 ? 2 : fraction > 0.1 ? 3 : 4;
    }

    private static final class State {
        final Fighter fighter;
        final int[] pp;
        double hp;
        boolean acted, recharge;
        int last = -1, charged = -1, sleep;
        boolean frozen;
        State(Fighter fighter, List<Attack> moves) {
            this.fighter = fighter;
            hp = fighter.hp;
            pp = moves.stream().mapToInt(attack -> attack.move.pp()).toArray();
            sleep = fighter.status.equals("slp") ? 2 : 0;
            frozen = fighter.status.equals("frz");
        }
        void enter() { acted = false; last = -1; charged = -1; recharge = false; }
    }

    static Estimate estimate(Model model) {
        Random random = new Random(0xC0BB1E);
        int wins = 0, losses = 0, unresolved = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            int result = battle(model, random);
            if (result > 0) wins++;
            else if (result < 0) losses++;
            else unresolved++;
        }
        return new Estimate(wins, losses, unresolved);
    }

    private static int battle(Model model, Random random) {
        State[] states = new State[model.fighters.size()];
        for (int i = 0; i < states.length; i++) {
            int target = i < model.allies ? model.allies : 0;
            states[i] = new State(model.fighters.get(i), model.attacks[i][target]);
        }
        int own = model.lead, foe = model.allies;
        for (int turn = 0; turn < 160; turn++) {
            if (states[own].hp <= 0) { own = replacement(model, states, 0, model.allies, foe); if (own < 0) return java.util.Arrays.stream(states).skip(model.allies).noneMatch(state -> state.hp > 0) ? 0 : -1; states[own].enter(); }
            if (states[foe].hp <= 0) { foe = replacement(model, states, model.allies, states.length, own); if (foe < 0) return 1; states[foe].enter(); }
            int a = choose(model, states, own, foe), b = choose(model, states, foe, own);
            // An emergency switch spends this turn taking the opponent's action.
            if (states[own].acted && expected(model, states, foe, own, b) >= states[own].hp) {
                int next = replacement(model, states, 0, model.allies, foe);
                if (next >= 0 && next != own && expected(model, states, foe, next, choose(model, states, foe, next)) < states[next].hp
                        && expected(model, states, own, foe, a) < states[foe].hp) {
                    own = next; states[own].enter(); a = -1;
                }
            }
            int priorityA = a < 0 ? Integer.MIN_VALUE : model.attacks[own][foe].get(a).move.priority();
            int priorityB = b < 0 ? Integer.MIN_VALUE : model.attacks[foe][own].get(b).move.priority();
            boolean first = priorityA != priorityB ? priorityA > priorityB : states[own].fighter.speed != states[foe].fighter.speed
                    ? states[own].fighter.speed > states[foe].fighter.speed : random.nextBoolean();
            if (first) { act(model, states, own, foe, a, random); act(model, states, foe, own, b, random); }
            else { act(model, states, foe, own, b, random); act(model, states, own, foe, a, random); }
            for (int index : new int[] {own, foe}) {
                State state = states[index];
                if (state.hp > 0) state.hp = Math.min(state.fighter.maxHp, state.hp - state.fighter.residual);
            }
        }
        return 0;
    }

    private static int replacement(Model model, State[] states, int start, int end, int target) {
        int best = -1;
        double score = -Double.MAX_VALUE;
        for (int i = start; i < end; i++) {
            if (states[i].hp <= 0) continue;
            double outgoing = expected(model, states, i, target, choose(model, states, i, target));
            double incoming = expected(model, states, target, i, choose(model, states, target, i));
            double value = (outgoing > 0 ? 100 : 0) + (incoming == 0 ? 50 : 20 * Math.min(2, states[i].hp / incoming))
                    + 10 * outgoing / Math.max(1, states[target].hp) + states[i].hp / states[i].fighter.maxHp;
            if (value > score) { score = value; best = i; }
        }
        return best;
    }

    private static int choose(Model model, State[] states, int user, int target) {
        State a = states[user], b = states[target];
        List<Attack> attacks = model.attacks[user][target];
        if (a.recharge) return -1;
        if (a.charged >= 0) return a.charged;
        int best = -1;
        double score = 0;
        for (int i = 0; i < attacks.size(); i++) {
            BattlePlanner.Action move = attacks.get(i).move;
            if (a.pp[i] <= 0 || move.opener() && a.acted || move.cooldown() && a.last == i
                    || a.fighter.choice && a.last >= 0 && a.last != i) continue;
            double damage = expected(model, states, user, target, i);
            double value = damage + (damage >= b.hp ? b.hp * 4 : 0);
            double heal = Math.min(a.fighter.maxHp - a.hp, move.effect().heal());
            if (a.hp < a.fighter.maxHp / 2) value = Math.max(value, heal * 0.8);
            if (value > score) { score = value; best = i; }
        }
        return best;
    }

    private static double expected(Model model, State[] states, int user, int target, int index) {
        if (index < 0) return 0;
        State a = states[user], b = states[target];
        Attack attack = model.attacks[user][target].get(index);
        return attack.damage(a.hp, a.fighter.maxHp, b.hp, b.fighter.maxHp) * attack.move.accuracy()
                / (attack.move.charging() || attack.move.recharge() ? 2 : 1);
    }

    private static void act(Model model, State[] states, int user, int target, int index, Random random) {
        State a = states[user], b = states[target];
        if (a.hp <= 0 || b.hp <= 0) return;
        if (a.recharge) { a.recharge = false; return; }
        if (index < 0) return;
        if (a.sleep > 0) { a.sleep--; a.acted = true; return; }
        if (a.frozen) {
            if (random.nextDouble() < 0.8) { a.acted = true; return; }
            a.frozen = false;
        }
        if (a.fighter.status.equals("par") && random.nextDouble() < 0.25) { a.acted = true; return; }
        Attack attack = model.attacks[user][target].get(index);
        BattlePlanner.Action move = attack.move;
        if (a.charged != index) a.pp[index]--;
        a.acted = true;
        a.last = index;
        if (move.charging() && a.charged != index) { a.charged = index; return; }
        a.charged = -1;
        a.recharge = move.recharge();
        if (random.nextDouble() >= move.accuracy()) return;
        double hit = attack.damage(a.hp, a.fighter.maxHp, b.hp, b.fighter.maxHp) * (0.85 + random.nextInt(16) * 0.01);
        hit = Math.min(b.hp, hit);
        b.hp -= hit;
        a.hp = Math.min(a.fighter.maxHp, a.hp + hit * move.effect().drain() + move.effect().heal()) - hit * move.effect().recoil();
        if (move.effect().selfStatus().equals("slp")) a.sleep = 2;
    }
}
