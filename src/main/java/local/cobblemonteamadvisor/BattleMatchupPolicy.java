package local.cobblemonteamadvisor;

import java.util.LinkedHashSet;
import java.util.List;

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

    static double stageMultiplier(int stage) {
        stage = Math.max(-6, Math.min(6, stage));
        return stage >= 0 ? (2.0D + stage) / 2 : 2.0D / (2 - stage);
    }

    static boolean actsFirst(int priority, double speed, int enemyPriority, double enemySpeed, boolean trickRoom) {
        if (priority != enemyPriority) return priority > enemyPriority;
        return trickRoom ? speed < enemySpeed : speed > enemySpeed;
    }

    static double accuracy(double accuracy, int attackerStage, int defenderStage) {
        if (accuracy <= 0 || accuracy > 100) return 1;
        int stage = Math.max(-6, Math.min(6, attackerStage - defenderStage));
        double modifier = stage >= 0 ? (3.0D + stage) / 3 : 3.0D / (3 - stage);
        return Math.min(1, accuracy / 100 * modifier);
    }

    static int survivalScore(double hp, double maxHp, double entryDamage, double incoming, double residual) {
        double remaining = hp - entryDamage;
        if (remaining <= 0) return -100;
        if (remaining <= incoming + residual) return -60;
        return (int) Math.round(6 * remaining / maxHp
                + 4 * Math.min(3, remaining / Math.max(1, incoming + residual)));
    }

    static double attackScore(double damageFraction, double accuracy, boolean ko, boolean firstKo,
                              double healingFraction, double recoilFraction, boolean diesBefore, boolean diesFromRecoil) {
        double score = Math.min(1.5D, damageFraction) * accuracy + (ko ? 3 * accuracy : 0)
                + (firstKo ? 2 : 0) + healingFraction - recoilFraction;
        if (diesBefore && !firstKo) score -= 6;
        if (diesFromRecoil) score -= ko ? 1.5D : 4;
        return score;
    }

    static double healingScore(double hp, double maxHp, double healing, double incoming, double residual) {
        healing = Math.max(0, Math.min(healing, maxHp - hp));
        if (healing == 0) return 0;
        double score = hp > incoming + residual ? healing / maxHp * 2.2D
                : hp - incoming - residual + healing > 0 ? 3 : -3;
        if (healing <= incoming + residual && hp > incoming + residual) score *= 0.4D;
        return score;
    }

    record MoveKnowledge(List<String> names, boolean complete, String source) { }

    static MoveKnowledge resolveMoves(List<String> revealed, List<String> trainer, List<String> species, List<String> fallback) {
        List<String> known = List.copyOf(new LinkedHashSet<>(revealed));
        if (known.size() >= 4) return new MoveKnowledge(known, true, "revealed set");
        List<String> exact = List.copyOf(new LinkedHashSet<>(trainer));
        if (!exact.isEmpty() && exact.size() <= 4 && exact.containsAll(known)) {
            return new MoveKnowledge(exact, true, "trainer set");
        }
        LinkedHashSet<String> assumed = new LinkedHashSet<>(known);
        assumed.addAll(exact);
        assumed.addAll(species);
        assumed.addAll(fallback);
        return new MoveKnowledge(List.copyOf(assumed), false, "assumed moves");
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
