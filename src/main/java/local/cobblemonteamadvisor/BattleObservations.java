package local.cobblemonteamadvisor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class BattleObservations {
    record DamageKey(UUID attacker, UUID defender, String move, String context) { }
    record SpeedKey(UUID first, UUID second, String context) { }
    private record Sample(double ratio, int count) { }
    private final Map<DamageKey, Sample> damage = new HashMap<>();
    private final Map<SpeedKey, Integer> speed = new HashMap<>();

    void damage(DamageKey key, double predicted, double observed, boolean clean, boolean fainted) {
        // A KO hides excess damage; residuals, crits, substitutes and multi-hit moves cannot calibrate a clean hit.
        if (!clean || fainted || predicted <= 0 || observed <= 0) return;
        double ratio = Math.max(0.25, Math.min(4, observed / predicted));
        Sample previous = damage.get(key);
        int count = previous == null ? 0 : Math.min(5, previous.count);
        double mean = previous == null ? ratio : (previous.ratio * count + ratio) / (count + 1);
        damage.put(key, new Sample(mean, count + 1));
    }

    double damageFactor(DamageKey key) {
        Sample sample = damage.get(key);
        return sample == null ? 1 : sample.ratio;
    }

    void order(UUID first, UUID second, String context, int firstPriority, int secondPriority, boolean clean) {
        if (!clean || first.equals(second) || firstPriority != secondPriority) return;
        SpeedKey key = new SpeedKey(first, second, context);
        SpeedKey reverse = new SpeedKey(second, first, context);
        speed.merge(key, 1, Integer::sum);
        if (speed.containsKey(reverse)) {
            // Conflicting order may indicate a speed tie. Do not promise either Pokemon acts first.
            speed.put(key, 0);
            speed.put(reverse, 0);
        }
    }

    Boolean first(UUID first, UUID second, String context) {
        Integer forward = speed.get(new SpeedKey(first, second, context));
        Integer reverse = speed.get(new SpeedKey(second, first, context));
        if (forward != null && forward >= 2) return true;
        if (reverse != null && reverse >= 2) return false;
        return null;
    }
}
