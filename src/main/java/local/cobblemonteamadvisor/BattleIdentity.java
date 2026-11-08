package local.cobblemonteamadvisor;

import java.util.List;

/** Resolve structured ownership, never by stripping an English possessive prefix. */
final class BattleIdentity {
    record Candidate(String pokemon, String actor, String owner, String name) { }

    static List<Candidate> match(String display, String owner, String ownedName, List<Candidate> candidates) {
        return candidates.stream().filter(c -> owner == null ? c.name.equals(display)
                : c.owner.equals(owner) && c.name.equals(ownedName)).toList();
    }
}
