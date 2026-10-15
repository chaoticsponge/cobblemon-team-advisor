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

    static boolean emergency(boolean threatened, boolean canDamage, boolean replacementCanDamage,
                             boolean survivesFollowup, double currentRisk, double nextRisk) {
        // Danger alone must not unlock a chain of switches into the same losing position.
        return replacementCanDamage && survivesFollowup
                && (!canDamage || threatened && nextRisk < 1 && nextRisk <= currentRisk - 0.25);
    }

    static boolean viable(boolean survivesEntry, boolean acts, boolean useful) {
        return survivesEntry && acts && useful;
    }

    static double healthAdjustment(double currentFraction, double replacementFraction) {
        // Compare changes in total party health, not a fresh bench member's HP against a wounded active.
        return 25 * (currentFraction - replacementFraction);
    }

    static boolean worthwhile(double gain, int cost) {
        return Double.isFinite(gain) && gain >= 6 + cost;
    }
}
