package local.cobblemonteamadvisor;

final class BattleMatchupPolicy {
    private BattleMatchupPolicy() {
    }

    static int offensiveScore(double multiplier) {
        if (multiplier >= 4.0D) return 4;
        if (multiplier >= 2.0D) return 2;
        if (multiplier <= 0.25D) return -2;
        if (multiplier < 1.0D) return -1;
        return 0;
    }

    static int threatScore(double multiplier) {
        // Even resisted outgoing damage against a resistant defender beats neutral defence
        // with super-effective offence. Immunity receives the largest defensive bonus.
        if (multiplier == 0.0D) return -16;
        if (multiplier <= 0.25D) return -12;
        if (multiplier < 1.0D) return -8;
        if (multiplier >= 4.0D) return 12;
        if (multiplier >= 2.0D) return 8;
        return 0;
    }

    static boolean shouldSwitch(int currentScore, int switchScore) {
        // A small improvement margin avoids spending a turn switching for a tie.
        return switchScore - currentScore >= 2;
    }

    static double effectiveMultiplier(double multiplier, String type, String defenderAbility, String attackerAbility) {
        if (defenderAbility == null || "moldbreaker".equals(attackerAbility)
                || "teravolt".equals(attackerAbility) || "turboblaze".equals(attackerAbility)) {
            return multiplier;
        }
        boolean immune = switch (defenderAbility) {
            case "levitate", "eartheater" -> "ground".equals(type);
            case "flashfire", "wellbakedbody" -> "fire".equals(type);
            case "waterabsorb", "stormdrain", "dryskin" -> "water".equals(type);
            case "voltabsorb", "lightningrod", "motordrive" -> "electric".equals(type);
            case "sapsipper" -> "grass".equals(type);
            default -> false;
        };
        if (immune) return 0.0D;
        if ("thickfat".equals(defenderAbility) && ("fire".equals(type) || "ice".equals(type))) {
            return multiplier * 0.5D;
        }
        if ("dryskin".equals(defenderAbility) && "fire".equals(type)) {
            return multiplier * 1.25D;
        }
        return multiplier;
    }
}
