package local.cobblemonteamadvisor;

final class RareEncounterPolicy {
    static boolean owned(String progress) {
        // Cobblemon 1.8 renamed CAUGHT to OWNED. Avoid linking either version's enum field.
        return "CAUGHT".equals(progress) || "OWNED".equals(progress);
    }

    static String reason(boolean shiny, boolean legendary, boolean caught, boolean raid) {
        if (raid) return null; // Raid bosses must be defeated before their capture phase.
        return shiny ? "shiny" : legendary ? "legendary" : !caught ? "uncaught" : null;
    }
}
