package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Mod(value = CobblemonTeamAdvisor.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CobblemonTeamAdvisor.MOD_ID, value = Dist.CLIENT)
public final class CobblemonTeamAdvisor {
    public static final String MOD_ID = "cobblemonteamadvisor";
    private static final int AUTO_TOGGLE_WIDTH = 58;
    private static final int AUTO_TOGGLE_HEIGHT = 14;
    private static final int AUTO_TOGGLE_MARGIN = 6;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, List<String>> COBBLEVERSE_TRAINER_MOVES = cobbleverseTrainerMoves();
    private static final Map<String, Map<String, List<String>>> COBBLEVERSE_TRAINER_MOVES_BY_NAME = cobbleverseTrainerMovesByName();
    private static boolean autoFightEnabled;
    private static long lastAutoActionAt;
    private static boolean wasPcOpen;

    public CobblemonTeamAdvisor() {
        TrainerBattleTracker.initialize();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        BattleAdvisor.syncBattle();
        PokedexScanAdvisor.tick();
        TrainerWinPreview.tick();
        Minecraft minecraft = Minecraft.getInstance();
        boolean pcOpen = minecraft.screen instanceof PCGUI;
        if (pcOpen && !wasPcOpen) {
            export(minecraft, (PCGUI) minecraft.screen);
        }
        wasPcOpen = pcOpen;

        if (BattleMedicine.tick(autoFightEnabled)) return;
        if (autoFightEnabled && minecraft.screen instanceof BattleGUI battleGUI) {
            autoSelectBattleAction(battleGUI);
        }
    }

    @SubscribeEvent
    public static void onPcInitialized(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof PCGUI pc) event.addListener(net.minecraft.client.gui.components.Button
                .builder(Component.literal("Loadouts"), button -> Minecraft.getInstance().setScreen(new LoadoutScreen(pc)))
                .bounds(Math.max(4, pc.width - 104), 6, 98, 20).build());
    }

    @SubscribeEvent
    public static void onTrainerRendered(net.neoforged.neoforge.client.event.RenderLivingEvent.Post<?, ?> event) {
        TrainerWinPreview.render(event);
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
        BattleMoveSelection moves = battleGUI.getCurrentActionSelection() instanceof BattleMoveSelection selection ? selection
                : battleGUI.getCurrentActionSelection() instanceof BattleGeneralActionSelection selection
                ? new BattleMoveSelection(battleGUI, selection.getRequest()) : null;
        if (moves != null) {
            BattleSwitchPokemonSelection switches = new BattleSwitchPokemonSelection(battleGUI, moves.getRequest());
            BattleSwitchPokemonSelection.SwitchTile best = bestSwitchTile(switches, currentOpponent(moves));
            BattleAdvisor.renderHint(event.getGuiGraphics(), moves, best);
        }
        renderAutoToggle(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof BattleGUI) || event.getButton() != 0 || !isAutoToggleHovered(event.getMouseX(), event.getMouseY())) {
            return;
        }

        autoFightEnabled = !autoFightEnabled;
        if (autoFightEnabled) RareEncounterGuard.allowPausedEncounter();
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
            BattleAdvisor.renderSwitchHint(graphics, bestTile, opponent.getBattlePokemon());
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
                String text = BattleAdvisor.moveAdvice(tile).koLabel();
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

    public static boolean keepBattleResponsive() {
        return autoFightEnabled && com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null;
    }

    private static void autoSelectBattleAction(BattleGUI battleGUI) {
        int protection = RareEncounterGuard.check();
        if (protection != 0) { if (protection == 2) autoFightEnabled = false; return; }
        if (!BattleAdvisor.freshSelection(battleGUI)) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoActionAt < 1200L) {
            return;
        }

        if (battleGUI.getCurrentActionSelection() instanceof BattleMoveSelection selection) {
            if (BattleMedicine.tryUse(battleGUI, selection)) { lastAutoActionAt = now; return; }
            if (openAutoSwitchIfNeeded(battleGUI, selection)) {
                lastAutoActionAt = now;
                return;
            }

            BattleMoveSelection.MoveTile tile = bestMoveTile(selection);
            if (tile != null) {
                TrainerBattleTracker.automatedAction();
                BattleAdvisor.traceMove(tile);
                tile.onClick();
                lastAutoActionAt = now;
            }
        } else if (battleGUI.getCurrentActionSelection() instanceof BattleSwitchPokemonSelection selection) {
            BattleSwitchPokemonSelection.SwitchTile tile = bestSwitchTile(selection, currentOpponent(selection));
            if (!selection.getRequest().getForceSwitch()) {
                BattleMoveSelection moves = new BattleMoveSelection(battleGUI, selection.getRequest());
                if (tile == null || !BattleAdvisor.shouldSwitch(moves, tile.getPokemon(), tile.getShowdownPokemon(), currentOpponent(selection))) {
                    battleGUI.changeActionSelection(moves);
                    return;
                }
            }
            if (tile != null) {
                if (BattleAdvisor.canSwitch(selection.getRequest(), tile.getPokemon(), tile.getShowdownPokemon())
                        && selection.mousePrimaryClicked(tile.getX() + BattleSwitchPokemonSelection.SwitchTile.SELECT_WIDTH / 2.0D,
                        tile.getY() + BattleSwitchPokemonSelection.SwitchTile.SELECT_HEIGHT / 2.0D)) {
                    TrainerBattleTracker.automatedAction();
                    BattleAdvisor.submittedSwitch(selection.getRequest(), tile.getPokemon().getUuid());
                }
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

    static ClientBattlePokemon currentOpponent(BattleMoveSelection selection) {
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
        if (opponent == null || active == null || selection.getMoveSet().getTrapped()) {
            return false;
        }

        BattleSwitchPokemonSelection switchSelection = new BattleSwitchPokemonSelection(battleGUI, selection.getRequest());
        BattleSwitchPokemonSelection.SwitchTile bestSwitch = bestSwitchTile(switchSelection, opponent);
        if (bestSwitch == null) {
            return false;
        }

        BattleSwitchPokemonSelection.SwitchTile recommended = bestSwitch;
        return BattleAdvisor.shouldSwitch(selection, recommended.getPokemon(), recommended.getShowdownPokemon(), opponent);
    }

    static Pokemon activePokemon(BattleMoveSelection selection) {
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
            if (pokemon == null || tile.isFainted() || tile.isCurrentlyInBattle()
                    || !BattleAdvisor.canSwitch(selection.getRequest(), pokemon, tile.getShowdownPokemon())) {
                continue;
            }

            if (!selection.getRequest().getForceSwitch()
                    && !BattleAdvisor.viableSwitch(pokemon, tile.getShowdownPokemon(), opponent)) continue;
            int score = matchupScore(pokemon, tile.getShowdownPokemon(), opponent);
            if (score > bestScore) {
                bestScore = score;
                bestTile = tile;
            }
        }
        return bestTile;
    }

    private static int matchupScore(Pokemon mine, ShowdownPokemon mineBattle, ClientBattlePokemon opponent) {
        return BattleAdvisor.matchup(mine, mineBattle, opponent, mineBattle == null || mineBattle.getActive()).score();
    }

    static BattleMatchupPolicy.MoveKnowledge opponentMoves(ClientBattlePokemon pokemon) {
        List<String> known = BattleAdvisor.knownMoves(pokemon);
        TrainerRoster.Member member = BattleAdvisor.trainerMember(pokemon);
        List<String> trainer = member == null ? trainerSpecificMoves(pokemon) : member.moves();
        List<String> revealed = known == null ? List.of() : known.stream().map(CobblemonTeamAdvisor::normalizedId).toList();
        var precise = BattleMatchupPolicy.resolveMoves(revealed, trainer, List.of(), List.of());
        if (precise.complete()) return precise;
        List<String> fallback = new ArrayList<>();
        for (MoveTemplate move : pokemon.getSpecies().getForm(pokemon.getState().getCurrentAspects()).getMoves()
                .getLevelUpMovesUpTo(pokemon.getLevel())) {
            fallback.add(move.getName());
        }
        return BattleMatchupPolicy.resolveMoves(revealed, trainer,
                COBBLEVERSE_TRAINER_MOVES.getOrDefault(trainerMoveKey(pokemon), List.of()), fallback);
    }

    private static String trainerMoveKey(ClientBattlePokemon pokemon) {
        return normalizedId(pokemon.getSpecies().getName()) + ":" + pokemon.getLevel();
    }

    private static List<String> trainerSpecificMoves(ClientBattlePokemon pokemon) {
        if (pokemon.getActor() == null || pokemon.getActor().getDisplayName() == null) {
            return List.of();
        }

        String actorName = normalizedId(pokemon.getActor().getDisplayName().getString());
        String memberKey = trainerMoveKey(pokemon);
        Map<String, List<String>> exact = COBBLEVERSE_TRAINER_MOVES_BY_NAME.get(actorName);
        if (exact != null && exact.containsKey(memberKey)) {
            return exact.get(memberKey);
        }

        String longest = "";
        List<String> best = List.of();
        for (var entry : COBBLEVERSE_TRAINER_MOVES_BY_NAME.entrySet()) {
            if (entry.getKey().length() >= 3 && entry.getKey().length() > longest.length() && actorName.contains(entry.getKey())
                    && entry.getValue().containsKey(memberKey)) {
                longest = entry.getKey();
                best = entry.getValue().get(memberKey);
            }
        }
        return best;
    }

    private static BattleMoveSelection.MoveTile bestMoveTile(BattleMoveSelection selection) {
        BattleMoveSelection.MoveTile bestTile = null;
        double bestScore = Double.NEGATIVE_INFINITY;
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
        return BattleAdvisor.moveAdvice(tile).score();
    }

    private static boolean likelyKos(BattleMoveSelection.MoveTile tile) {
        return BattleAdvisor.moveAdvice(tile).ko();
    }

    static ActiveClientBattlePokemon target(BattleMoveSelection.MoveTile tile) {
        List<Targetable> targets = tile.getTargetList();
        if (targets != null) {
            for (Targetable target : targets) {
                if (target instanceof ActiveClientBattlePokemon active && active.hasPokemon()
                        && !active.isAllied(tile.getMoveSelection().getRequest().getActivePokemon())) {
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

    static double moveMultiplier(ElementalType attackType, Collection<ElementalType> defenderTypes) {
        double multiplier = 1;
        for (ElementalType defender : defenderTypes) multiplier *= TypeMatchups.multiplier(attackType.showdownId(), defender.showdownId());
        return multiplier;
    }

    static String normalizedId(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String signed(int score) {
        return (score >= 0 ? "+" : "") + score;
    }

    private static Map<String, List<String>> cobbleverseTrainerMoves() {
        JsonObject root = cobbleverseTrainerMoveData();
        if (root == null) {
            return Map.of();
        }

        JsonObject moves = root.getAsJsonObject("moves");
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : moves.entrySet()) {
            result.put(entry.getKey(), stringList(entry.getValue().getAsJsonArray()));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Map<String, List<String>>> cobbleverseTrainerMovesByName() {
        JsonObject root = cobbleverseTrainerMoveData();
        if (root == null || !root.has("trainerMovesByName")) {
            return Map.of();
        }

        JsonObject trainers = root.getAsJsonObject("trainerMovesByName");
        Map<String, Map<String, List<String>>> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> trainer : trainers.entrySet()) {
            Map<String, List<String>> members = new HashMap<>();
            for (Map.Entry<String, JsonElement> member : trainer.getValue().getAsJsonObject().entrySet()) {
                members.put(member.getKey(), stringList(member.getValue().getAsJsonArray()));
            }
            result.put(trainer.getKey(), Map.copyOf(members));
        }
        return Map.copyOf(result);
    }

    private static JsonObject cobbleverseTrainerMoveData() {
        try (InputStream stream = CobblemonTeamAdvisor.class.getResourceAsStream("/cobbleverse-trainer-moves.json")) {
            if (stream == null) {
                return null;
            }

            return GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception exception) {
            LOGGER.warn("Could not load Cobbleverse trainer move snapshot", exception);
            return null;
        }
    }

    private static List<String> stringList(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }
}
