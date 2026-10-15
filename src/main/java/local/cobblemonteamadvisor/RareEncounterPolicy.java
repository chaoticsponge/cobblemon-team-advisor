package local.cobblemonteamadvisor;

final class RareEncounterPolicy {
    static String reason(boolean shiny, boolean legendary, boolean caught, boolean raid) {
        if (raid) return null; // Raid bosses must be defeated before their capture phase.
        return shiny ? "shiny" : legendary ? "legendary" : !caught ? "uncaught" : null;
    }
}
