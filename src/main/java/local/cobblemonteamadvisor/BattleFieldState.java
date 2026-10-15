package local.cobblemonteamadvisor;

final class BattleFieldState {
    static final class Side {
        int spikes;
        int toxicSpikes;
        boolean rocks;
        boolean web;
        boolean tailwind;
        boolean reflect;
        boolean lightScreen;
        boolean auroraVeil;

        void update(String effect, boolean start) {
            switch (effect) {
                case "spikes" -> spikes = start ? Math.min(3, spikes + 1) : 0;
                case "toxicspikes" -> toxicSpikes = start ? Math.min(2, toxicSpikes + 1) : 0;
                case "stealthrock" -> rocks = start;
                case "stickyweb" -> web = start;
                case "tailwind" -> tailwind = start;
                case "reflect" -> reflect = start;
                case "lightscreen" -> lightScreen = start;
                case "auroraveil" -> auroraVeil = start;
                default -> { }
            }
        }

        double entryFraction(double rockMultiplier, boolean grounded, boolean boots, boolean magicGuard) {
            if (boots || magicGuard) return 0;
            double result = rocks ? rockMultiplier / 8.0D : 0;
            if (grounded && spikes > 0) result += switch (spikes) {
                case 1 -> 1.0D / 8;
                case 2 -> 1.0D / 6;
                default -> 1.0D / 4;
            };
            return result;
        }
    }

    Side ally = new Side();
    Side opponent = new Side();
    boolean trickRoom;
    boolean gravity;
    String weather = "";
    String terrain = "";

    double weatherDamage(String type) {
        boolean rain = weather.equals("raindance") || weather.equals("primordialsea");
        boolean sun = weather.equals("sunnyday") || weather.equals("desolateland");
        if (weather.equals("primordialsea") && type.equals("fire") || weather.equals("desolateland") && type.equals("water")) return 0;
        if (type.equals("water")) return rain ? 1.5 : sun ? 0.5 : 1;
        if (type.equals("fire")) return sun ? 1.5 : rain ? 0.5 : 1;
        return 1;
    }

    double terrainDamage(String type, String move, boolean attackerGrounded, boolean defenderGrounded, int priority) {
        if (terrain.equals("psychicterrain") && defenderGrounded && priority > 0) return 0;
        double modifier = attackerGrounded && (terrain.equals("electricterrain") && type.equals("electric")
                || terrain.equals("grassyterrain") && type.equals("grass")
                || terrain.equals("psychicterrain") && type.equals("psychic")) ? 1.3 : 1;
        if (terrain.equals("mistyterrain") && defenderGrounded && type.equals("dragon")) modifier *= 0.5;
        if (terrain.equals("grassyterrain") && defenderGrounded
                && (move.equals("earthquake") || move.equals("bulldoze") || move.equals("magnitude"))) modifier *= 0.5;
        return modifier;
    }

    void observe(String key) {
        String prefix = "cobblemon.battle.";
        if (!key.startsWith(prefix)) return;
        String[] parts = key.substring(prefix.length()).split("\\.");
        if (parts.length == 3 && (parts[0].equals("sidestart") || parts[0].equals("sideend"))) {
            if (parts[1].equals("ally") || parts[1].equals("opponent")) {
                (parts[1].equals("ally") ? ally : opponent).update(parts[2], parts[0].equals("sidestart"));
            }
        } else if (parts.length == 2) {
            if (parts[0].equals("fieldstart") || parts[0].equals("fieldend")) {
                boolean start = parts[0].equals("fieldstart");
                if (parts[1].equals("trickroom")) trickRoom = start;
                if (parts[1].equals("gravity")) gravity = start;
                if (parts[1].endsWith("terrain")) {
                    if (start) terrain = parts[1];
                    else if (terrain.equals(parts[1])) terrain = "";
                }
            } else if (parts[0].equals("activate") && parts[1].equals("courtchange")) {
                Side previous = ally;
                ally = opponent;
                opponent = previous;
            }
        } else if (parts.length == 3 && parts[0].equals("weather")) {
            if (parts[2].equals("start") || parts[2].equals("upkeep")) weather = parts[1];
            else if (parts[2].equals("end") && weather.equals(parts[1])) weather = "";
        }
    }
}
