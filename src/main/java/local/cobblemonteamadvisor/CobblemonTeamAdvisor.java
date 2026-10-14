package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.moves.categories.DamageCategories;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.ShowdownPokemon;
import com.cobblemon.mod.common.battles.Targetable;
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile;
import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.client.storage.ClientBox;
import com.cobblemon.mod.common.client.storage.ClientPC;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Mod(value = CobblemonTeamAdvisor.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CobblemonTeamAdvisor.MOD_ID, value = Dist.CLIENT)
public final class CobblemonTeamAdvisor {
    public static final String MOD_ID = "cobblemonteamadvisor";
    private static final int AUTO_TOGGLE_WIDTH = 58;
    private static final int AUTO_TOGGLE_HEIGHT = 14;
    private static final int AUTO_TOGGLE_MARGIN = 6;
    private static final int SWITCH_OUT_THRESHOLD = -2;
    private static final int SWITCH_GAIN_THRESHOLD = 2;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Map<String, Double>> TYPE_EFFECTIVENESS = typeEffectiveness();
    private static final Map<String, List<String>> COBBLEVERSE_TRAINER_MOVES = cobbleverseTrainerMoves();
    private static boolean autoFightEnabled;
    private static long lastAutoActionAt;
    private static boolean wasPcOpen;

    public CobblemonTeamAdvisor() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean pcOpen = minecraft.screen instanceof PCGUI;
        if (pcOpen && !wasPcOpen) {
            export(minecraft, (PCGUI) minecraft.screen);
        }
        wasPcOpen = pcOpen;

        if (autoFightEnabled && minecraft.screen instanceof BattleGUI battleGUI) {
            autoSelectBattleAction(battleGUI);
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof BattleGUI battleGUI)) {
            return;
        }

        if (battleGUI.getCurrentActionSelection() instanceof BattleSwitchPokemonSelection selection) {
            renderSwitchScores(event.getGuiGraphics(), selection);
        } else if (battleGUI.getCurrentActionSelection() instanceof BattleMoveSelection selection) {
            renderMoveAdvisor(event.getGuiGraphics(), selection);
        }
        renderAutoToggle(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof BattleGUI) || event.getButton() != 0 || !isAutoToggleHovered(event.getMouseX(), event.getMouseY())) {
            return;
        }

        autoFightEnabled = !autoFightEnabled;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("Auto fight " + (autoFightEnabled ? "enabled" : "disabled")), true);
        }
        event.setCanceled(true);
    }

    private static void renderSwitchScores(GuiGraphics graphics, BattleSwitchPokemonSelection selection) {
        ActiveClientBattlePokemon opponent = selection.getRequest().getActivePokemon().getAdjacentOpponents().stream()
                .filter(ActiveClientBattlePokemon.class::isInstance)
                .map(ActiveClientBattlePokemon.class::cast)
                .filter(ActiveClientBattlePokemon::hasPokemon)
                .findFirst()
                .orElse(null);
        if (opponent == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        BattleSwitchPokemonSelection.SwitchTile bestTile = bestSwitchTile(selection, opponent.getBattlePokemon());
        for (BattleSwitchPokemonSelection.SwitchTile tile : selection.getTiles()) {
            Pokemon pokemon = tile.getPokemon();
            if (pokemon == null || tile.isFainted()) {
                continue;
            }

            int score = matchupScore(pokemon, tile.getShowdownPokemon(), opponent.getBattlePokemon());
            String text = signed(score);
            int color = score > 0 ? 0xFF5BFF73 : score < 0 ? 0xFFFF6B6B : 0xFFE8E8E8;
            int x = Math.round(tile.getX()) + 22 + minecraft.font.width(Integer.toString(pokemon.getLevel()));
            int y = Math.round(tile.getY()) + 4;
            graphics.drawString(minecraft.font, text, x, y, color, true);
        }
        if (bestTile != null) {
            outline(graphics, Math.round(bestTile.getX()), Math.round(bestTile.getY()),
                    BattleSwitchPokemonSelection.SwitchTile.SELECT_WIDTH, BattleSwitchPokemonSelection.SwitchTile.SELECT_HEIGHT);
        }
    }

    private static void renderMoveAdvisor(GuiGraphics graphics, BattleMoveSelection selection) {
        Minecraft minecraft = Minecraft.getInstance();
        BattleMoveSelection.MoveTile bestTile = bestMoveTile(selection);
        for (BattleMoveSelection.MoveTile tile : selection.getMoveTiles()) {
            if (!tile.getSelectable()) {
                continue;
            }

            if (likelyKos(tile)) {
                String text = "KO";
                int x = Math.round(tile.getX()) + BattleMoveSelection.MOVE_WIDTH - minecraft.font.width(text) - 5;
                int y = Math.round(tile.getY()) + 3;
                graphics.drawString(minecraft.font, text, x, y, 0xFFFFE066, true);
            }
        }
        if (bestTile != null) {
            int x = Math.round(bestTile.getX());
            int y = Math.round(bestTile.getY());
            outline(graphics, x, y, BattleMoveSelection.MOVE_WIDTH, BattleMoveSelection.MOVE_HEIGHT);
        }
    }

    private static void renderAutoToggle(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        int x = autoToggleX();
        int y = AUTO_TOGGLE_MARGIN;
        int background = autoFightEnabled ? 0xCC1F6F3A : 0xCC292929;
        int border = autoFightEnabled ? 0xFF5BFF73 : 0xFFE8E8E8;
        String text = autoFightEnabled ? "AUTO ON" : "AUTO OFF";

        graphics.fill(x, y, x + AUTO_TOGGLE_WIDTH, y + AUTO_TOGGLE_HEIGHT, background);
        graphics.renderOutline(x, y, AUTO_TOGGLE_WIDTH, AUTO_TOGGLE_HEIGHT, border);
        graphics.drawString(minecraft.font, text, x + 5, y + 3, 0xFFFFFFFF, true);
    }

    private static boolean isAutoToggleHovered(double mouseX, double mouseY) {
        int x = autoToggleX();
        int y = AUTO_TOGGLE_MARGIN;
        return mouseX >= x && mouseX <= x + AUTO_TOGGLE_WIDTH && mouseY >= y && mouseY <= y + AUTO_TOGGLE_HEIGHT;
    }

    private static int autoToggleX() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth() - AUTO_TOGGLE_WIDTH - AUTO_TOGGLE_MARGIN;
    }

    private static void outline(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.renderOutline(x, y, width, height, 0xFF5BFF73);
    }

    private static void autoSelectBattleAction(BattleGUI battleGUI) {
        long now = System.currentTimeMillis();
        if (now - lastAutoActionAt < 1200L) {
            return;
        }

        if (battleGUI.getCurrentActionSelection() instanceof BattleMoveSelection selection) {
            if (openAutoSwitchIfNeeded(battleGUI, selection)) {
                lastAutoActionAt = now;
                return;
            }

            BattleMoveSelection.MoveTile tile = bestMoveTile(selection);
            if (tile != null) {
                tile.onClick();
                lastAutoActionAt = now;
            }
        } else if (battleGUI.getCurrentActionSelection() instanceof BattleSwitchPokemonSelection selection) {
            BattleSwitchPokemonSelection.SwitchTile tile = bestSwitchTile(selection, currentOpponent(selection));
            if (tile != null) {
                selection.mousePrimaryClicked(tile.getX() + BattleSwitchPokemonSelection.SwitchTile.SELECT_WIDTH / 2.0D,
                        tile.getY() + BattleSwitchPokemonSelection.SwitchTile.SELECT_HEIGHT / 2.0D);
                lastAutoActionAt = now;
            }
        } else if (battleGUI.getCurrentActionSelection() instanceof BattleGeneralActionSelection selection) {
            BattleMoveSelection moveSelection = new BattleMoveSelection(battleGUI, selection.getRequest());
            String action = shouldAutoSwitch(battleGUI, moveSelection) ? "switch" : "fight";
            if (clickActionTile(selection, action) || (!"fight".equals(action) && clickActionTile(selection, "fight"))) {
                lastAutoActionAt = now;
            }
        }
    }

    private static boolean clickActionTile(BattleGeneralActionSelection selection, String action) {
        for (BattleOptionTile tile : selection.getTiles()) {
            if (normalizedId(tile.getText().getString()).contains(action)) {
                return tile.mousePrimaryClicked(tile.getX() + 45.0D, tile.getY() + 13.0D);
            }
        }
        return false;
    }

    private static void export(Minecraft minecraft, PCGUI screen) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        try {
            JsonObject document = new JsonObject();
            document.addProperty("formatVersion", 1);
            document.addProperty("exportedAt", Instant.now().toString());
            document.addProperty("player", minecraft.getUser().getName());

            List<String[]> csvRows = new ArrayList<>();
            JsonArray party = new JsonArray();
            for (int slot = 0; slot < screen.getParty().getSlots().size(); slot++) {
                Pokemon pokemon = screen.getParty().getSlots().get(slot);
                if (pokemon != null) {
                    party.add(pokemonEntry(pokemon, "Party", slot + 1, minecraft, csvRows));
                }
            }
            document.add("party", party);

            JsonArray boxes = new JsonArray();
            ClientPC pc = screen.getPc();
            for (int boxIndex = 0; boxIndex < pc.getBoxes().size(); boxIndex++) {
                ClientBox box = pc.getBoxes().get(boxIndex);
                String boxName = boxName(box, boxIndex);
                JsonObject boxData = new JsonObject();
                boxData.addProperty("index", boxIndex + 1);
                boxData.addProperty("name", boxName);
                JsonArray pokemonInBox = new JsonArray();
                for (int slot = 0; slot < box.getSlots().size(); slot++) {
                    Pokemon pokemon = box.getSlots().get(slot);
                    if (pokemon != null) {
                        pokemonInBox.add(pokemonEntry(pokemon, boxName, slot + 1, minecraft, csvRows));
                    }
                }
                boxData.add("pokemon", pokemonInBox);
                boxes.add(boxData);
            }
            document.add("boxes", boxes);

            Path jsonPath = minecraft.gameDirectory.toPath().resolve("pokemon-export.json");
            Path csvPath = minecraft.gameDirectory.toPath().resolve("pokemon-export.csv");
            Files.writeString(jsonPath, GSON.toJson(document), StandardCharsets.UTF_8);
            Files.writeString(csvPath, toCsv(csvRows), StandardCharsets.UTF_8);
            minecraft.player.displayClientMessage(Component.literal(
                    "Exported " + csvRows.size() + " Pokemon to pokemon-export.json and pokemon-export.csv"), false);
        } catch (Exception exception) {
            LOGGER.error("Could not export Cobblemon data", exception);
            minecraft.player.displayClientMessage(Component.literal("Pokemon export failed; see latest.log"), false);
        }
    }

    private static JsonObject pokemonEntry(Pokemon pokemon, String box, int slot, Minecraft minecraft,
                                           List<String[]> csvRows) {
        JsonObject entry = new JsonObject();
        entry.addProperty("box", box);
        entry.addProperty("slot", slot);
        entry.add("pokemon", pokemon.saveToJSON(minecraft.level.registryAccess(), new JsonObject()));

        csvRows.add(new String[]{
                box,
                Integer.toString(slot),
                pokemon.getUuid().toString(),
                pokemon.getSpecies().getName(),
                pokemonName(pokemon),
                Integer.toString(pokemon.getLevel()),
                pokemon.getGender().toString(),
                Boolean.toString(pokemon.getShiny()),
                pokemon.getNature().toString()
        });
        return entry;
    }

    private static String toCsv(List<String[]> rows) {
        StringBuilder csv = new StringBuilder("Box,Slot,UUID,Species,Nickname,Level,Gender,Shiny,Nature\n");
        for (String[] row : rows) {
            for (int column = 0; column < row.length; column++) {
                if (column > 0) {
                    csv.append(',');
                }
                csv.append('"').append(row[column].replace("\"", "\"\"")).append('"');
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    private static String pokemonName(Pokemon pokemon) {
        return pokemon.getNickname() == null ? pokemon.getSpecies().getName() : pokemon.getNickname().getString();
    }

    private static String boxName(ClientBox box, int boxIndex) {
        return box.getName() == null ? "Box " + (boxIndex + 1) : box.getName().getString();
    }

    private static ClientBattlePokemon currentOpponent(BattleSwitchPokemonSelection selection) {
        ActiveClientBattlePokemon opponent = selection.getRequest().getActivePokemon().getAdjacentOpponents().stream()
                .filter(ActiveClientBattlePokemon.class::isInstance)
                .map(ActiveClientBattlePokemon.class::cast)
                .filter(ActiveClientBattlePokemon::hasPokemon)
                .findFirst()
                .orElse(null);
        return opponent == null ? null : opponent.getBattlePokemon();
    }

    private static ClientBattlePokemon currentOpponent(BattleMoveSelection selection) {
        ActiveClientBattlePokemon opponent = selection.getRequest().getActivePokemon().getAdjacentOpponents().stream()
                .filter(ActiveClientBattlePokemon.class::isInstance)
                .map(ActiveClientBattlePokemon.class::cast)
                .filter(ActiveClientBattlePokemon::hasPokemon)
                .findFirst()
                .orElse(null);
        return opponent == null ? null : opponent.getBattlePokemon();
    }

    private static boolean openAutoSwitchIfNeeded(BattleGUI battleGUI, BattleMoveSelection selection) {
        if (!shouldAutoSwitch(battleGUI, selection)) {
            return false;
        }

        battleGUI.changeActionSelection(new BattleSwitchPokemonSelection(battleGUI, selection.getRequest()));
        return true;
    }

    private static boolean shouldAutoSwitch(BattleGUI battleGUI, BattleMoveSelection selection) {
        ClientBattlePokemon opponent = currentOpponent(selection);
        Pokemon active = activePokemon(selection);
        if (opponent == null || active == null) {
            return false;
        }

        BattleSwitchPokemonSelection switchSelection = new BattleSwitchPokemonSelection(battleGUI, selection.getRequest());
        BattleSwitchPokemonSelection.SwitchTile bestSwitch = bestSwitchTile(switchSelection, opponent);
        if (bestSwitch == null) {
            return false;
        }

        int currentScore = matchupScore(active, null, opponent);
        int switchScore = matchupScore(bestSwitch.getPokemon(), bestSwitch.getShowdownPokemon(), opponent);
        return currentScore <= SWITCH_OUT_THRESHOLD && switchScore - currentScore >= SWITCH_GAIN_THRESHOLD;
    }

    private static Pokemon activePokemon(BattleMoveSelection selection) {
        for (BattleMoveSelection.MoveTile tile : selection.getMoveTiles()) {
            Pokemon pokemon = tile.getPokemon();
            if (pokemon != null) {
                return pokemon;
            }
        }
        return null;
    }

    private static BattleSwitchPokemonSelection.SwitchTile bestSwitchTile(BattleSwitchPokemonSelection selection,
                                                                          ClientBattlePokemon opponent) {
        if (opponent == null) {
            return null;
        }

        BattleSwitchPokemonSelection.SwitchTile bestTile = null;
        int bestScore = Integer.MIN_VALUE;
        for (BattleSwitchPokemonSelection.SwitchTile tile : selection.getTiles()) {
            Pokemon pokemon = tile.getPokemon();
            if (pokemon == null || tile.isFainted() || tile.isCurrentlyInBattle()) {
                continue;
            }

            int score = matchupScore(pokemon, tile.getShowdownPokemon(), opponent);
            if (score > bestScore) {
                bestScore = score;
                bestTile = tile;
            }
        }
        return bestTile;
    }

    private static int matchupScore(Pokemon mine, ShowdownPokemon mineBattle, ClientBattlePokemon opponent) {
        int score = offensiveScore(mine, mineBattle, opponent);
        score -= offensiveThreatScore(opponent, mine);
        return Math.max(-9, Math.min(9, score));
    }

    private static int offensiveScore(Pokemon attacker, ShowdownPokemon battleData, ClientBattlePokemon defender) {
        double best = 0.0D;
        boolean hasDamagingMove = false;
        for (Move move : attacker.getMoveSet()) {
            if (move == null || move.getDamageCategory() == DamageCategories.INSTANCE.getSTATUS() || move.getPower() <= 0.0D) {
                continue;
            }
            hasDamagingMove = true;
            best = Math.max(best, moveMultiplier(move.getTemplate().getEffectiveElementalType(attacker), defenderTypes(defender)));
        }

        if (!hasDamagingMove && battleData != null) {
            for (String moveName : battleData.getMoves()) {
                MoveTemplate move = Moves.getByName(moveName);
                if (move != null && move.getDamageCategory() != DamageCategories.INSTANCE.getSTATUS() && move.getPower() > 0.0D) {
                    hasDamagingMove = true;
                    best = Math.max(best, moveMultiplier(move.getElementalType(), defenderTypes(defender)));
                }
            }
        }

        if (!hasDamagingMove) {
            best = bestTypeMultiplier(types(attacker), defenderTypes(defender));
        }

        int score = multiplierScore(best, true);
        String defenderAbility = normalizedAbility(defender.getProperties().getAbility());
        if (defenderAbilityCounters(defenderAbility, attackTypes(attacker), defenderTypes(defender))) {
            score--;
        }
        return score;
    }

    private static int offensiveThreatScore(ClientBattlePokemon attacker, Pokemon defender) {
        List<ElementalType> defenderTypes = types(defender);
        List<ElementalType> attackTypes = new ArrayList<>();
        double best = bestTypeMultiplier(defenderTypes(attacker), defenderTypes);

        for (MoveTemplate move : opponentThreatMoves(attacker)) {
            ElementalType type = move.getElementalType();
            attackTypes.add(type);
            best = Math.max(best, moveMultiplier(type, defenderTypes));
        }

        int score = multiplierScore(best, false);
        if (defenderAbilityCounters(normalizedAbility(defender.getAbility().getName()), attackTypes, defenderTypes)) {
            score--;
        }
        return score;
    }

    private static List<MoveTemplate> opponentThreatMoves(ClientBattlePokemon pokemon) {
        List<MoveTemplate> moves = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> knownMoves = pokemon.getProperties().getMoves();
        if (knownMoves != null) {
            for (String moveName : knownMoves) {
                MoveTemplate move = damagingMove(moveName);
                if (move != null && seen.add(move.getName())) {
                    moves.add(move);
                }
            }
        }

        if (moves.size() < 4) {
            List<String> trainerMoves = COBBLEVERSE_TRAINER_MOVES.get(trainerMoveKey(pokemon));
            if (trainerMoves != null) {
                for (String moveName : trainerMoves) {
                    MoveTemplate move = damagingMove(moveName);
                    if (move != null && seen.add(move.getName())) {
                        moves.add(move);
                    }
                }
            }
        }

        if (moves.size() < 4) {
            Set<String> aspects = pokemon.getProperties().getAspects();
            for (MoveTemplate move : pokemon.getSpecies().getForm(aspects).getMoves().getLevelUpMovesUpTo(pokemon.getLevel())) {
                if (isDamaging(move) && seen.add(move.getName())) {
                    moves.add(move);
                }
            }
        }
        return moves;
    }

    private static String trainerMoveKey(ClientBattlePokemon pokemon) {
        return normalizedId(pokemon.getSpecies().getName()) + ":" + pokemon.getLevel();
    }

    private static MoveTemplate damagingMove(String moveName) {
        MoveTemplate move = Moves.getByName(moveName);
        return isDamaging(move) ? move : null;
    }

    private static boolean isDamaging(MoveTemplate move) {
        return move != null && move.getDamageCategory() != DamageCategories.INSTANCE.getSTATUS() && move.getPower() > 0.0D;
    }

    private static BattleMoveSelection.MoveTile bestMoveTile(BattleMoveSelection selection) {
        BattleMoveSelection.MoveTile bestTile = null;
        double bestScore = 0.0D;
        for (BattleMoveSelection.MoveTile tile : selection.getMoveTiles()) {
            if (!tile.getSelectable()) {
                continue;
            }

            double score = moveScore(tile);
            if (score > bestScore) {
                bestScore = score;
                bestTile = tile;
            }
        }
        return bestTile;
    }

    private static double moveScore(BattleMoveSelection.MoveTile tile) {
        Pokemon attacker = tile.getPokemon();
        MoveTemplate move = tile.getMoveTemplate();
        if (attacker == null || !isDamaging(move)) {
            return 0.0D;
        }

        ActiveClientBattlePokemon target = target(tile);
        if (target == null || !target.hasPokemon()) {
            return 0.0D;
        }

        ClientBattlePokemon defender = target.getBattlePokemon();
        double damageRatio = estimatedDamage(attacker, defender, move, tile.getElementalType()) / estimatedMaxHp(defender);
        return likelyKos(tile) ? damageRatio + 2.0D : damageRatio;
    }

    private static boolean likelyKos(BattleMoveSelection.MoveTile tile) {
        Pokemon attacker = tile.getPokemon();
        MoveTemplate move = tile.getMoveTemplate();
        if (attacker == null || !isDamaging(move)) {
            return false;
        }

        ActiveClientBattlePokemon target = target(tile);
        if (target == null || !target.hasPokemon()) {
            return false;
        }

        ClientBattlePokemon defender = target.getBattlePokemon();
        double currentHpRatio = defender.getMaxHp() <= 0.0F ? 1.0D : defender.getHpValue() / defender.getMaxHp();
        double damageRatio = estimatedDamage(attacker, defender, move, tile.getElementalType()) / estimatedMaxHp(defender);
        return damageRatio >= currentHpRatio;
    }

    private static ActiveClientBattlePokemon target(BattleMoveSelection.MoveTile tile) {
        List<Targetable> targets = tile.getTargetList();
        if (targets != null) {
            for (Targetable target : targets) {
                if (target instanceof ActiveClientBattlePokemon active && active.hasPokemon()) {
                    return active;
                }
            }
        }
        return tile.getMoveSelection().getRequest().getActivePokemon().getAdjacentOpponents().stream()
                .filter(ActiveClientBattlePokemon.class::isInstance)
                .map(ActiveClientBattlePokemon.class::cast)
                .filter(ActiveClientBattlePokemon::hasPokemon)
                .findFirst()
                .orElse(null);
    }

    private static double estimatedDamage(Pokemon attacker, ClientBattlePokemon defender, MoveTemplate move,
                                          ElementalType attackType) {
        int level = Math.max(1, attacker.getLevel());
        double power = adjustedPower(move, attacker, defender);
        boolean special = move.getDamageCategory() == DamageCategories.INSTANCE.getSPECIAL();
        double attack = Math.max(1, attacker.getStat(special ? Stats.SPECIAL_ATTACK : Stats.ATTACK));
        double defence = Math.max(1, estimatedStat(defender, special ? Stats.SPECIAL_DEFENCE : Stats.DEFENCE));
        double stab = types(attacker).stream().anyMatch(type -> type == attackType || type.showdownId().equals(attackType.showdownId())) ? 1.5D : 1.0D;
        double type = moveMultiplier(attackType, defenderTypes(defender));
        double base = (((2.0D * level / 5.0D + 2.0D) * power * attack / defence) / 50.0D) + 2.0D;
        return base * stab * type * 0.925D;
    }

    private static double adjustedPower(MoveTemplate move, Pokemon attacker, ClientBattlePokemon defender) {
        String name = move.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        double power = move.getPower();
        boolean defenderStatused = defender.getStatus() != null;
        boolean attackerStatused = attacker.getStatus() != null && attacker.getStatus().getStatus() != null;
        double defenderHpRatio = defender.getMaxHp() <= 0.0F ? 1.0D : defender.getHpValue() / defender.getMaxHp();

        if (("hex".equals(name) || "venoshock".equals(name) || "infernalparade".equals(name)
                || "barbbarrage".equals(name)) && defenderStatused) {
            power *= 2.0D;
        } else if ("facade".equals(name) && attackerStatused) {
            power *= 2.0D;
        } else if ("brine".equals(name) && defenderHpRatio <= 0.5D) {
            power *= 2.0D;
        }
        return power;
    }

    private static double estimatedMaxHp(ClientBattlePokemon pokemon) {
        return Math.max(1, estimatedHp(pokemon));
    }

    private static int estimatedHp(ClientBattlePokemon pokemon) {
        int level = Math.max(1, pokemon.getLevel());
        int base = baseStat(pokemon, Stats.HP);
        return (int) Math.floor(((2 * base + 31) * level) / 100.0D) + level + 10;
    }

    private static int estimatedStat(ClientBattlePokemon pokemon, Stats stat) {
        int level = Math.max(1, pokemon.getLevel());
        int base = baseStat(pokemon, stat);
        return (int) Math.floor(((2 * base + 31) * level) / 100.0D) + 5;
    }

    private static int baseStat(ClientBattlePokemon pokemon, Stat stat) {
        Integer value = pokemon.getSpecies().getBaseStats().get(stat);
        return value == null ? 1 : value;
    }

    private static List<ElementalType> attackTypes(Pokemon pokemon) {
        List<ElementalType> types = new ArrayList<>();
        for (Move move : pokemon.getMoveSet()) {
            if (move != null && move.getDamageCategory() != DamageCategories.INSTANCE.getSTATUS() && move.getPower() > 0.0D) {
                types.add(move.getTemplate().getEffectiveElementalType(pokemon));
            }
        }
        return types.isEmpty() ? types(pokemon) : types;
    }

    private static List<ElementalType> types(Pokemon pokemon) {
        return iterableToList(pokemon.getTypes());
    }

    private static List<ElementalType> defenderTypes(ClientBattlePokemon pokemon) {
        return iterableToList(pokemon.getSpecies().getTypes());
    }

    private static List<ElementalType> iterableToList(Iterable<ElementalType> iterable) {
        List<ElementalType> types = new ArrayList<>();
        for (ElementalType type : iterable) {
            if (type != null) {
                types.add(type);
            }
        }
        return types;
    }

    private static double bestTypeMultiplier(Collection<ElementalType> attackTypes, Collection<ElementalType> defenderTypes) {
        double best = 0.0D;
        for (ElementalType attackType : attackTypes) {
            best = Math.max(best, moveMultiplier(attackType, defenderTypes));
        }
        return best;
    }

    private static double moveMultiplier(ElementalType attackType, Collection<ElementalType> defenderTypes) {
        String attack = attackType.showdownId();
        double multiplier = 1.0D;
        for (ElementalType defenderType : defenderTypes) {
            multiplier *= TYPE_EFFECTIVENESS.getOrDefault(attack, Map.of())
                    .getOrDefault(defenderType.showdownId(), 1.0D);
        }
        return multiplier;
    }

    private static int multiplierScore(double multiplier, boolean attacking) {
        if (multiplier >= 4.0D) {
            return attacking ? 4 : 2;
        }
        if (multiplier >= 2.0D) {
            return attacking ? 2 : 1;
        }
        if (multiplier == 0.0D) {
            return attacking ? -2 : -1;
        }
        if (multiplier <= 0.25D) {
            return attacking ? -2 : -1;
        }
        if (multiplier < 1.0D) {
            return attacking ? -1 : 0;
        }
        return 0;
    }

    private static boolean defenderAbilityCounters(String ability, Collection<ElementalType> attackTypes,
                                                   Collection<ElementalType> defenderTypes) {
        if (ability == null) {
            return false;
        }

        for (ElementalType attackType : attackTypes) {
            String type = attackType.showdownId();
            if (("levitate".equals(ability) && "ground".equals(type))
                    || ("flashfire".equals(ability) && "fire".equals(type))
                    || (("waterabsorb".equals(ability) || "stormdrain".equals(ability) || "dryskin".equals(ability)) && "water".equals(type))
                    || (("voltabsorb".equals(ability) || "lightningrod".equals(ability) || "motordrive".equals(ability)) && "electric".equals(type))
                    || ("sapsipper".equals(ability) && "grass".equals(type))
                    || ("eartheater".equals(ability) && "ground".equals(type))
                    || ("wellbakedbody".equals(ability) && "fire".equals(type))) {
                return true;
            }
        }

        return "thickfat".equals(ability) && attackTypes.stream()
                .filter(Objects::nonNull)
                .map(ElementalType::showdownId)
                .anyMatch(type -> "fire".equals(type) || "ice".equals(type));
    }

    private static String normalizedAbility(String ability) {
        return ability == null ? null : ability.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String normalizedId(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String signed(int score) {
        return (score >= 0 ? "+" : "") + score;
    }

    private static Map<String, Map<String, Double>> typeEffectiveness() {
        Map<String, Map<String, Double>> chart = new HashMap<>();
        put(chart, "normal", 0.0D, "ghost");
        put(chart, "normal", 0.5D, "rock", "steel");
        put(chart, "fire", 2.0D, "grass", "ice", "bug", "steel");
        put(chart, "fire", 0.5D, "fire", "water", "rock", "dragon");
        put(chart, "water", 2.0D, "fire", "ground", "rock");
        put(chart, "water", 0.5D, "water", "grass", "dragon");
        put(chart, "electric", 2.0D, "water", "flying");
        put(chart, "electric", 0.5D, "electric", "grass", "dragon");
        put(chart, "electric", 0.0D, "ground");
        put(chart, "grass", 2.0D, "water", "ground", "rock");
        put(chart, "grass", 0.5D, "fire", "grass", "poison", "flying", "bug", "dragon", "steel");
        put(chart, "ice", 2.0D, "grass", "ground", "flying", "dragon");
        put(chart, "ice", 0.5D, "fire", "water", "ice", "steel");
        put(chart, "fighting", 2.0D, "normal", "ice", "rock", "dark", "steel");
        put(chart, "fighting", 0.5D, "poison", "flying", "psychic", "bug", "fairy");
        put(chart, "fighting", 0.0D, "ghost");
        put(chart, "poison", 2.0D, "grass", "fairy");
        put(chart, "poison", 0.5D, "poison", "ground", "rock", "ghost");
        put(chart, "poison", 0.0D, "steel");
        put(chart, "ground", 2.0D, "fire", "electric", "poison", "rock", "steel");
        put(chart, "ground", 0.5D, "grass", "bug");
        put(chart, "ground", 0.0D, "flying");
        put(chart, "flying", 2.0D, "grass", "fighting", "bug");
        put(chart, "flying", 0.5D, "electric", "rock", "steel");
        put(chart, "psychic", 2.0D, "fighting", "poison");
        put(chart, "psychic", 0.5D, "psychic", "steel");
        put(chart, "psychic", 0.0D, "dark");
        put(chart, "bug", 2.0D, "grass", "psychic", "dark");
        put(chart, "bug", 0.5D, "fire", "fighting", "poison", "flying", "ghost", "steel", "fairy");
        put(chart, "rock", 2.0D, "fire", "ice", "flying", "bug");
        put(chart, "rock", 0.5D, "fighting", "ground", "steel");
        put(chart, "ghost", 2.0D, "psychic", "ghost");
        put(chart, "ghost", 0.5D, "dark");
        put(chart, "ghost", 0.0D, "normal");
        put(chart, "dragon", 2.0D, "dragon");
        put(chart, "dragon", 0.5D, "steel");
        put(chart, "dragon", 0.0D, "fairy");
        put(chart, "dark", 2.0D, "psychic", "ghost");
        put(chart, "dark", 0.5D, "fighting", "dark", "fairy");
        put(chart, "steel", 2.0D, "ice", "rock", "fairy");
        put(chart, "steel", 0.5D, "fire", "water", "electric", "steel");
        put(chart, "fairy", 2.0D, "fighting", "dragon", "dark");
        put(chart, "fairy", 0.5D, "fire", "poison", "steel");
        return chart;
    }

    private static void put(Map<String, Map<String, Double>> chart, String attack, double multiplier, String... defenders) {
        Map<String, Double> row = chart.computeIfAbsent(attack, key -> new HashMap<>());
        for (String defender : defenders) {
            row.put(defender, multiplier);
        }
    }

    private static Map<String, List<String>> cobbleverseTrainerMoves() {
        try (InputStream stream = CobblemonTeamAdvisor.class.getResourceAsStream("/cobbleverse-trainer-moves.json")) {
            if (stream == null) {
                return Map.of();
            }

            JsonObject root = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
            JsonObject moves = root.getAsJsonObject("moves");
            Map<String, List<String>> result = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : moves.entrySet()) {
                List<String> names = new ArrayList<>();
                for (JsonElement move : entry.getValue().getAsJsonArray()) {
                    names.add(move.getAsString());
                }
                result.put(entry.getKey(), List.copyOf(names));
            }
            return Map.copyOf(result);
        } catch (Exception exception) {
            LOGGER.warn("Could not load Cobbleverse trainer move snapshot", exception);
            return Map.of();
        }
    }
}
