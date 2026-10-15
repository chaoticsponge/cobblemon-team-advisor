package local.cobblemonteamadvisor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Voluntary switches must produce progress before another switch against the same opponent. */
final class BattleSwitchPolicy {
    private final Map<UUID, UUID> pending = new HashMap<>();
    private final Map<UUID, UUID> needsAction = new HashMap<>();

    void submitted(UUID destination, UUID opponent, boolean forced) {
        if (forced || opponent == null) pending.remove(destination);
        else pending.put(destination, opponent);
    }

    void entered(UUID pokemon) {
        needsAction.remove(pokemon);
        UUID opponent = pending.remove(pokemon);
        if (opponent != null) needsAction.put(pokemon, opponent);
    }

    void acted(UUID pokemon) {
        needsAction.remove(pokemon);
    }

    void rejected() {
        pending.clear();
    }

    boolean allows(UUID active, UUID opponent, boolean emergency) {
        return emergency || !opponent.equals(needsAction.get(active));
    }

    static boolean emergency(boolean threatened, boolean canDamage, boolean replacementCanDamage) {
        return threatened || !canDamage && replacementCanDamage;
    }

    static boolean worthwhile(double gain, int cost, int currentScore, int nextScore, double currentRisk, double nextRisk) {
        if (gain >= 6 + cost) return true;
        if (gain < -3) return false;
        // Tiny hits from weak opponents do not justify spending a turn chasing a type-score improvement.
        return currentRisk >= 0.25 && nextRisk <= currentRisk - 0.15
                && BattleMatchupPolicy.shouldSwitch(currentScore + cost, nextScore);
    }
}
