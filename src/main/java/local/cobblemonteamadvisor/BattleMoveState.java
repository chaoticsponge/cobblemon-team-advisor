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
        final Map<String, String> effects = new HashMap<>();
        String choiceMove = "";
        int protectTurn = -1;
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
        used(pokemon, move, false);
    }

    void used(UUID pokemon, String move, boolean choiceItem) {
        Entry entry = entry(pokemon);
        entry.acted = true;
        entry.lastMove = move;
        entry.used.add(move);
        if (!choiceItem) entry.choiceMove = "";
        else if (entry.choiceMove.isEmpty()) entry.choiceMove = move;
    }

    void attempted(UUID pokemon) {
        entry(pokemon).acted = true;
    }

    int turn() { return turn; }
    String lastMove(UUID pokemon) { return entry(pokemon).lastMove; }
    boolean hasEffect(UUID pokemon, String effect) { return entry(pokemon).effects.containsKey(effect); }
    String effectMove(UUID pokemon, String effect) { return entry(pokemon).effects.getOrDefault(effect, ""); }
    boolean protectedNow(UUID pokemon) { return entry(pokemon).protectTurn == turn; }

    void effect(UUID pokemon, String effect, boolean start, String move) {
        Entry entry = entry(pokemon);
        if (effect.equals("protect")) { entry.protectTurn = start ? turn : -1; return; }
        if (start) entry.effects.put(effect, move == null || move.isEmpty() ? entry.lastMove : move);
        else entry.effects.remove(effect);
    }

    String restricted(UUID pokemon, String move, boolean statusMove, boolean choiceItem) {
        Entry entry = entry(pokemon);
        if (statusMove && hasEffect(pokemon, "taunt")) return "Taunt prevents status moves";
        if (move.equals(entry.effects.get("disable"))) return "move is disabled";
        String encore = entry.effects.get("encore");
        if (encore != null && !encore.isEmpty() && !move.equals(encore)) return "Encore locks another move";
        if (choiceItem && !entry.choiceMove.isEmpty() && !move.equals(entry.choiceMove)) return "choice item locks another move";
        return null;
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
