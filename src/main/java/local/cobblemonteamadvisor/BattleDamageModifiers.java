package local.cobblemonteamadvisor;

import java.util.Set;

/** Shared move and ability modifiers for either side of every battle. */
final class BattleDamageModifiers {
    record User(String ability, String item, String status, double hpFraction, String weather) {
        User { if (item.equals("air") || item.equals("minecraftair")) item = ""; }
    }
    record Move(String id, String type, String originalType, double power, boolean special, Set<String> flags) {
        boolean has(String flag) { return flags.contains(flag); }
    }

    private BattleDamageModifiers() { }

    static boolean changesType(String move) {
        return !Set.of("hiddenpower", "judgment", "multiattack", "technoblast", "naturalgift", "revelationdance",
                "terrainpulse", "weatherball", "struggle").contains(move);
    }

    static String type(String original, String ability) {
        if (ability.equals("normalize")) return "normal";
        if (!original.equals("normal")) return original;
        return switch (ability) {
            case "aerilate" -> "flying";
            case "galvanize" -> "electric";
            case "pixilate" -> "fairy";
            case "refrigerate" -> "ice";
            default -> original;
        };
    }

    static double power(User user, User target, Move move) {
        return switch (move.id) {
            case "acrobatics" -> move.power * (user.item.isEmpty() ? 2 : 1);
            case "hex", "venoshock", "infernalparade", "barbbarrage" -> move.power
                    * (!target.status.isEmpty() && (!Set.of("venoshock", "barbbarrage").contains(move.id)
                    || Set.of("psn", "tox").contains(target.status)) ? 2 : 1);
            case "facade" -> move.power * (Set.of("brn", "psn", "tox", "par").contains(user.status) ? 2 : 1);
            case "brine" -> move.power * (target.hpFraction <= 0.5 ? 2 : 1);
            case "eruption", "waterspout", "dragonenergy" -> Math.max(1, Math.floor(150 * user.hpFraction));
            case "wringout", "crushgrip", "hardpress" -> Math.max(1, Math.floor((move.id.equals("hardpress") ? 100 : 120) * target.hpFraction));
            case "flail", "reversal" -> user.hpFraction < 2.0 / 48 ? 200 : user.hpFraction < 5.0 / 48 ? 150
                    : user.hpFraction < 10.0 / 48 ? 100 : user.hpFraction < 17.0 / 48 ? 80 : user.hpFraction < 11.0 / 16 ? 40 : 20;
            default -> move.power;
        };
    }

    static double powerFactor(User user, Move move) {
        double factor = switch (user.ability) {
            case "technician" -> move.power <= 60 ? 1.5 : 1;
            case "toughclaws" -> move.has("contact") && !(user.item.endsWith("punchingglove") && move.has("punch")) ? 1.3 : 1;
            case "ironfist" -> move.has("punch") ? 1.2 : 1;
            case "strongjaw" -> move.has("bite") ? 1.5 : 1;
            case "megalauncher" -> move.has("pulse") ? 1.5 : 1;
            case "sharpness" -> move.has("slicing") ? 1.5 : 1;
            case "punkrock" -> move.has("sound") ? 1.3 : 1;
            case "sheerforce" -> move.has("secondary") ? 1.3 : 1;
            case "reckless" -> move.has("recoil") ? 1.2 : 1;
            case "sandforce" -> user.weather.equals("sandstorm") && Set.of("rock", "ground", "steel").contains(move.type) ? 1.3 : 1;
            case "steelworker", "steelyspirit" -> move.type.equals("steel") ? 1.5 : 1;
            case "dragonmaw" -> move.type.equals("dragon") ? 1.5 : 1;
            case "blaze" -> user.hpFraction <= 1.0 / 3 && move.type.equals("fire") ? 1.5 : 1;
            case "torrent" -> user.hpFraction <= 1.0 / 3 && move.type.equals("water") ? 1.5 : 1;
            case "overgrow" -> user.hpFraction <= 1.0 / 3 && move.type.equals("grass") ? 1.5 : 1;
            case "swarm" -> user.hpFraction <= 1.0 / 3 && move.type.equals("bug") ? 1.5 : 1;
            case "normalize" -> changesType(move.id) ? 1.2 : 1;
            case "aerilate", "galvanize", "pixilate", "refrigerate" -> move.originalType.equals("normal") && changesType(move.id) ? 1.2 : 1;
            case "toxicboost" -> !move.special && Set.of("psn", "tox").contains(user.status) ? 1.5 : 1;
            case "flareboost" -> move.special && user.status.equals("brn") ? 1.5 : 1;
            default -> 1;
        };
        if (user.item.endsWith("muscleband") && !move.special || user.item.endsWith("wiseglasses") && move.special) factor *= 1.1;
        if (user.item.endsWith("punchingglove") && move.has("punch")) factor *= 1.1;
        return factor;
    }

    static double attackFactor(User user, Move move) {
        return switch (user.ability) {
            case "hustle", "gorillatactics" -> !move.special ? 1.5 : 1;
            case "defeatist" -> user.hpFraction <= 0.5 ? 0.5 : 1;
            case "waterbubble" -> move.type.equals("water") ? 2 : 1;
            case "transistor" -> move.type.equals("electric") ? 1.3 : 1;
            default -> 1;
        };
    }

    static boolean immune(String ability, Move move, boolean bypass) {
        if (bypass) return false;
        return ability.equals("soundproof") && move.has("sound") || ability.equals("bulletproof") && move.has("bullet")
                || ability.equals("windrider") && move.has("wind");
    }

    static double defenceFactor(User target, boolean physicalDefence, boolean bypass) {
        double factor = target.item.endsWith("assaultvest") && !physicalDefence ? 1.5 : 1;
        if (!bypass && physicalDefence) {
            if (target.ability.equals("furcoat")) factor *= 2;
            if (target.ability.equals("marvelscale") && !target.status.isEmpty()) factor *= 1.5;
        }
        return factor;
    }

    static double damageFactor(User user, User target, Move move, double effectiveness, boolean bypass) {
        double factor = user.ability.equals("tintedlens") && effectiveness > 0 && effectiveness < 1 ? 2 : 1;
        if (user.item.endsWith("expertbelt") && effectiveness > 1) factor *= 1.2;
        // Prism Armor and Shadow Shield cannot be bypassed by Mold Breaker.
        if (target.ability.equals("prismarmor") && effectiveness > 1) factor *= 0.75;
        if (target.ability.equals("shadowshield") && target.hpFraction >= 1) factor *= 0.5;
        if (!bypass) factor *= switch (target.ability) {
            case "filter", "solidrock" -> effectiveness > 1 ? 0.75 : 1;
            case "multiscale" -> target.hpFraction >= 1 ? 0.5 : 1;
            case "icescales" -> move.special ? 0.5 : 1;
            case "punkrock" -> move.has("sound") ? 0.5 : 1;
            case "heatproof", "waterbubble" -> move.type.equals("fire") ? 0.5 : 1;
            case "fluffy" -> (move.type.equals("fire") ? 2 : 1) * (move.has("contact") && !user.ability.equals("longreach") && !(user.item.endsWith("punchingglove") && move.has("punch")) ? 0.5 : 1);
            case "purifyingsalt" -> move.type.equals("ghost") ? 0.5 : 1;
            default -> 1;
        };
        return factor;
    }
}
