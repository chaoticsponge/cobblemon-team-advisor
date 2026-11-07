package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.battles.InBattleGimmickMove;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Uses the server's offered buttons and native move responses; never grants a gimmick locally. */
final class BattleGimmicks {
    private static final JsonObject POWERS = loadPowers();
    private static BattleGimmickPolicy policy = new BattleGimmickPolicy();
    private static SingleActionRequest submitted;
    private static BattleMoveSelection cachedSelection;
    private static List<BattleMoveSelection.MoveTile> cachedTiles;
    private static final Map<BattleMoveSelection.MoveTile, MoveTemplate> templates = new java.util.IdentityHashMap<>();
    private static final java.util.Set<MoveTemplate> poweredTemplates = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final java.lang.reflect.Constructor<?> constructor = java.util.Arrays.stream(MoveTemplate.class.getConstructors())
            .filter(c -> c.getParameterCount() == 11 || c.getParameterCount() == 12).findFirst().orElseThrow();

    private static JsonObject loadPowers() {
        try (var input = BattleGimmicks.class.getResourceAsStream("/battle-gimmick-moves.json")) {
            if (input == null) throw new IllegalStateException("Missing powered-move metadata");
            return new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot load powered-move metadata", error); }
    }

    static void reset() { policy = new BattleGimmickPolicy(); submitted = null; cachedSelection = null; cachedTiles = null; templates.clear(); poweredTemplates.clear(); }
    static void sync(SingleActionRequest request) {
        if (submitted != null && request != null && submitted != request) { policy.answered(); submitted = null; }
    }
    static void rejected() { policy.rejected(); submitted = null; }

    static String id(BattleMoveSelection.MoveTile tile) {
        String id = tile.getResponse().getGimmickID();
        return id == null ? "" : id;
    }

    static List<BattleMoveSelection.MoveTile> tiles(BattleMoveSelection selection) {
        if (cachedSelection == selection) return cachedTiles;
        templates.clear(); poweredTemplates.clear();
        List<BattleMoveSelection.MoveTile> result = new ArrayList<>(selection.getBaseTiles());
        for (var button : selection.getGimmickButtons()) for (var tile : button.getTiles()) {
            String gimmick = id(tile);
            if (legal(tile, gimmick)) result.add(BattleGimmickPolicy.powered(gimmick) ? new PoweredTile(tile, gimmick, mapped(tile, gimmick)) : tile);
        }
        cachedSelection = selection; cachedTiles = List.copyOf(result);
        return cachedTiles;
    }

    static boolean selectable(BattleMoveSelection.MoveTile tile) {
        String gimmick = id(tile);
        return gimmick.isEmpty() ? tile.getSelectable() : policy.allowed(policyKey(tile)) && legal(tile, gimmick);
    }

    private static String policyKey(BattleMoveSelection.MoveTile tile) {
        String gimmick = id(tile);
        return gimmick.equals("max") && tile.getMoveSelection().getMoveSet().hasActiveGimmick()
                ? "max:" + tile.getMove().getId() : gimmick;
    }

    static boolean legal(BattleMoveSelection.MoveTile tile, String gimmick) {
        var set = tile.getMoveSelection().getMoveSet();
        if (tile.getMove().getPp() <= 0) return false;
        return switch (gimmick) {
            case "mega" -> set.getCanMegaEvo() && tile.getSelectable();
            case "ultra" -> set.getCanUltraBurst() && tile.getSelectable();
            case "terastal" -> set.getCanTerastallize() != null && !set.getCanTerastallize().isBlank() && tile.getSelectable();
            case "zmove", "max" -> {
                var mapped = mapped(tile, gimmick);
                yield mapped != null && !mapped.getDisabled() && Moves.getByName(CobblemonTeamAdvisor.normalizedId(mapped.getMove())) != null
                        && (gimmick.equals("zmove") || set.getCanDynamax() || set.hasActiveGimmick());
            }
            default -> false;
        };
    }

    private static InBattleGimmickMove mapped(BattleMoveSelection.MoveTile tile, String gimmick) {
        var set = tile.getMoveSelection().getMoveSet();
        var moves = gimmick.equals("zmove") ? set.getCanZMove() : set.getMaxMoves();
        int index = set.getMoves().indexOf(tile.getMove());
        return moves == null || index < 0 || index >= moves.size() ? null : moves.get(index);
    }

    static boolean powered(MoveTemplate move) { return poweredTemplates.contains(move); }

    static MoveTemplate move(BattleMoveSelection.MoveTile tile) {
        return templates.computeIfAbsent(tile, BattleGimmicks::createMove);
    }

    private static MoveTemplate createMove(BattleMoveSelection.MoveTile tile) {
        String gimmick = id(tile);
        MoveTemplate base = Moves.getByName(tile.getMove().getId());
        if (base == null) return tile.getMoveTemplate();
        if (!BattleGimmickPolicy.powered(gimmick)) return base;
        var mapped = mapped(tile, gimmick);
        if (mapped == null) return base;
        MoveTemplate powered = Moves.getByName(CobblemonTeamAdvisor.normalizedId(mapped.getMove()));
        if (powered == null) return base; // Unknown custom move: do not invent its damage.
        var row = POWERS.getAsJsonArray(base.getName());
        double power = BattleGimmickPolicy.power(gimmick, powered.getPower(), row == null ? 0 : row.get(0).getAsInt(), row == null ? 0 : row.get(1).getAsInt());
        MoveTemplate result = template(powered, base, mapped, gimmick, power);
        poweredTemplates.add(result);
        return result;
    }

    private static MoveTemplate template(MoveTemplate powered, MoveTemplate base, InBattleGimmickMove mapped, String gimmick, double power) {
        java.util.List<Object> args = new ArrayList<>(List.of(powered.getName(), powered.getNum(), powered.getElementalType(),
                base.getDamageCategory(), power, mapped.getTarget(), powered.getAccuracy(), base.getPp(),
                gimmick.equals("zmove") ? base.getPriority() : 0, powered.getCritRatio(), powered.getEffectChances()));
        // 1.8 adds an AI selection-weight argument; it does not affect damage.
        if (constructor.getParameterCount() == 12) args.add(1F);
        try { return (MoveTemplate) constructor.newInstance(args.toArray()); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot construct powered move", error); }
    }

    private static final class PoweredTile extends BattleMoveSelection.MoveTile {
        private final String gimmick;
        private final InBattleGimmickMove mapped;
        PoweredTile(BattleMoveSelection.MoveTile tile, String gimmick, InBattleGimmickMove mapped) {
            super(tile.getMoveSelection(), tile.getMove(), tile.getX(), tile.getY());
            this.gimmick = gimmick; this.mapped = mapped;
            var template = move(tile);
            setMoveTemplate(template); templates.put(this, template);
        }
        @Override public boolean getSelectable() { return getMove().getPp() > 0 && mapped != null && !mapped.getDisabled(); }
        @Override public java.util.List<com.cobblemon.mod.common.battles.Targetable> getTargetList() {
            return mapped.getTarget().getTargetList().invoke(getMoveSelection().getRequest().getActivePokemon());
        }
        @Override public com.cobblemon.mod.common.battles.MoveActionResponse getResponse() {
            return new com.cobblemon.mod.common.battles.MoveActionResponse(getMove().getId(), getTargetPnx(), gimmick);
        }
    }

    static BattleMoveSelection.MoveTile best(BattleMoveSelection selection) {
        BattleMoveSelection.MoveTile best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (var tile : tiles(selection)) {
            String gimmick = id(tile);
            if (!selectable(tile)) continue;
            var advice = BattleAdvisor.moveAdvice(tile);
            double value = advice.score();
            // Evolving has lasting value even when the immediate hit is tied.
            if ((gimmick.equals("mega") || gimmick.equals("ultra")) && Double.isFinite(value)) value += 0.01;
            if (value > score) { score = value; best = tile; }
        }
        return best;
    }

    static void submit(BattleMoveSelection.MoveTile tile) {
        if (!selectable(tile) || !BattleAdvisor.freshSelection(tile.getMoveSelection().getBattleGUI())) return;
        String gimmick = id(tile);
        if (!gimmick.isEmpty()) {
            submitted = tile.getMoveSelection().getRequest(); policy.submitted(policyKey(tile));
            BattleTrace.event("auto_gimmick_submitted", Map.of("gimmick", gimmick, "move", move(tile).getName(),
                    "baseMove", tile.getMove().getId()));
        }
        // Native response retains the base move slot, target and exactly one gimmick ID.
        tile.getMoveSelection().getBattleGUI().selectAction(tile.getMoveSelection().getRequest(), tile.getResponse());
    }
}
