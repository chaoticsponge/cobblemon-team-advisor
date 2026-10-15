package local.cobblemonteamadvisor;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BattleActionGuard {
    private final Map<Object, Set<UUID>> rejected = new IdentityHashMap<>();

    void reject(Object request, UUID destination) {
        if (rejected.size() >= 32 && !rejected.containsKey(request)) rejected.clear();
        rejected.computeIfAbsent(request, ignored -> new HashSet<>()).add(destination);
    }

    boolean canSwitch(Object request, UUID destination, UUID current, Set<UUID> active,
                      boolean fainted, boolean trapped, boolean reserved) {
        return destination != null && !destination.equals(current) && !active.contains(destination)
                && !fainted && !trapped && !reserved
                && !rejected.getOrDefault(request, Set.of()).contains(destination);
    }
}
