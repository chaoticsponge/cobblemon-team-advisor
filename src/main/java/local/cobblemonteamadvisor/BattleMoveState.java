package local.cobblemonteamadvisor;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BattleMoveState {
    private static final Set<String> OPENERS = Set.of("firstimpression", "fakeout", "matblock");
    private static final Set<String> COOLDOWNS = Set.of("gigatonhammer", "bloodmoon");
    private static final class Entry {
        int firstTurn = -1;
        boolean acted;
        String lastMove = "";
        final Set<String> used = new HashSet<>();
    }

    private final Map<UUID, Entry> entries = new HashMap<>();
    private int turn;

    void enter(UUID pokemon) {
        entries.put(pokemon, new Entry());
    }

    void startTurn(int number, Collection<UUID> active) {
        if (number <= turn) return;
        turn = number;
        for (UUID pokemon : active) {
            Entry entry = entry(pokemon);
            if (entry.firstTurn < 0) entry.firstTurn = turn;
        }
    }

    void used(UUID pokemon, String move) {
        Entry entry = entry(pokemon);
        entry.acted = true;
        entry.lastMove = move;
        entry.used.add(move);
    }

    void attempted(UUID pokemon) {
        entry(pokemon).acted = true;
    }

    String unavailable(UUID pokemon, String move, List<String> moveset, boolean complete) {
        Entry entry = entry(pokemon);
        if (OPENERS.contains(move) && (entry.acted || entry.firstTurn > 0 && turn > entry.firstTurn)) {
            return "only works on the first turn after entering battle";
        }
        if (COOLDOWNS.contains(move) && entry.lastMove.equals(move)) return "cannot be used twice consecutively";
        if (move.equals("noretreat") && entry.used.contains(move)) return "already used during this field entry";
        if (move.equals("lastresort") && complete
                && (moveset.size() < 2 || moveset.stream().anyMatch(id -> !id.equals(move) && !entry.used.contains(id)))) {
            return "use every other move first";
        }
        return null;
    }

    private Entry entry(UUID pokemon) {
        return entries.computeIfAbsent(pokemon, ignored -> new Entry());
    }
}
