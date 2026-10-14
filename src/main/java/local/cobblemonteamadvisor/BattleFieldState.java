package local.cobblemonteamadvisor;

final class BattleFieldState {
    static final class Side {
        int spikes;
        int toxicSpikes;
        boolean rocks;
        boolean web;
        boolean tailwind;

        void update(String effect, boolean start) {
            switch (effect) {
                case "spikes" -> spikes = start ? Math.min(3, spikes + 1) : 0;
                case "toxicspikes" -> toxicSpikes = start ? Math.min(2, toxicSpikes + 1) : 0;
                case "stealthrock" -> rocks = start;
                case "stickyweb" -> web = start;
                case "tailwind" -> tailwind = start;
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
            } else if (parts[0].equals("activate") && parts[1].equals("courtchange")) {
                Side previous = ally;
                ally = opponent;
                opponent = previous;
            }
        }
    }
}
