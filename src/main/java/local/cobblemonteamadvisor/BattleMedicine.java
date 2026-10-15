package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.interact.partyselect.PartySelectGUI;
import com.cobblemon.mod.common.item.interactive.PotionItem;
import com.cobblemon.mod.common.item.interactive.StatusCureItem;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Uses ordinary inventory/item interactions and the server's own enabled-target callback. */
final class BattleMedicine {
    private static UUID battle, disabledBattle;
    private static final Set<SingleActionRequest> attempted = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Pending pending;
    private static SingleActionRequest completed;
    private static final class Pending {
        BattleGUI gui;
        SingleActionRequest request;
        UUID target;
        int targetIndex, source, hand, originalHand, originalCount;
        ItemStack medicine, previous;
        long deadline;
        boolean selected, consumed;
    }

    static boolean tryUse(BattleGUI gui, BattleMoveSelection selection) {
        Minecraft client = Minecraft.getInstance();
        var current = CobblemonClient.INSTANCE.getBattle();
        if (current == null || current.getSpectating() || current.getBattleId().equals(disabledBattle)
                || attempted.contains(selection.getRequest()) || client.player == null || client.gameMode == null
                || selection.getRequest().getForceSwitch() || client.player.containerMenu != client.player.inventoryMenu) return false;
        var active = CobblemonTeamAdvisor.activePokemon(selection);
        var state = BattleAdvisor.medicineState(selection);
        var actor = current.getParticipatingActor(client.player.getUUID());
        if (active == null || state == null || actor == null) return false;
        int targetIndex = -1;
        for (int i = 0; i < actor.getPokemon().size(); i++) if (actor.getPokemon().get(i).getUuid().equals(active.getUuid())) targetIndex = i;
        if (targetIndex < 0) return false;
        int bestSlot = -1; double bestGain = 8;
        Map<String, Double> benefits = new java.util.HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            double heal = 0; boolean cures = false;
            if (stack.getItem() instanceof PotionItem potion) {
                heal = potion.getType().getAmountToHeal().invoke().resolveInt(com.cobblemon.mod.common.util.MoLangExtensionsKt.getGenericRuntime());
                cures = potion.getType().getCuresStatus() && !state.status().isEmpty();
            } else if (stack.getItem() instanceof StatusCureItem cure) {
                for (var status : cure.getStatus()) if (status.getShowdownName().equals(state.status())) cures = true;
            } else continue;
            if (stack.isEmpty() || heal > 0 && state.hp() >= state.maxHp() && !cures || heal <= 0 && !cures) continue;
            String effect = heal + ":" + cures;
            Double gain = benefits.get(effect);
            if (gain == null) { gain = BattleAdvisor.medicineGain(selection, heal, cures); benefits.put(effect, gain); }
            if (gain > bestGain) { bestSlot = slot; bestGain = gain; }
        }
        if (bestSlot < 0) return false;
        attempted.add(selection.getRequest());
        Pending action = new Pending();
        action.gui = gui; action.request = selection.getRequest(); action.target = active.getUuid(); action.targetIndex = targetIndex;
        action.source = bestSlot; action.originalHand = client.player.getInventory().selected;
        action.hand = bestSlot < 9 ? bestSlot : action.originalHand;
        action.medicine = client.player.getInventory().getItem(bestSlot).copy(); action.originalCount = action.medicine.getCount();
        action.previous = client.player.getInventory().getItem(action.hand).copy(); action.deadline = System.currentTimeMillis() + 3000;
        pending = action;
        if (bestSlot >= 9) client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, bestSlot, action.hand, ClickType.SWAP, client.player);
        client.player.getInventory().selected = action.hand;
        BattleTrace.event("auto_item_requested", Map.of("item", action.medicine.getItem().toString(), "pokemon", action.target.toString(), "gain", bestGain));
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        return true;
    }

    static boolean tick(boolean enabled) {
        Minecraft client = Minecraft.getInstance();
        var current = CobblemonClient.INSTANCE.getBattle();
        UUID id = current == null ? null : current.getBattleId();
        if (!java.util.Objects.equals(battle, id)) {
            if (pending != null) finish(false);
            battle = id; disabledBattle = null; completed = null; attempted.clear();
        }
        if (pending == null) {
            if (completed != null && current != null && current.getFirstUnansweredRequest() == completed) return true;
            completed = null; return false;
        }
        Pending action = pending;
        if (!enabled || client.player == null || client.gameMode == null || current == null) { finish(false); return false; }
        ItemStack held = client.player.getInventory().getItem(action.hand);
        action.consumed |= held.isEmpty() || ItemStack.isSameItemSameComponents(held, action.medicine) && held.getCount() < action.originalCount;
        if (current.getFirstUnansweredRequest() != action.request) { finish(true); return true; }
        if (System.currentTimeMillis() > action.deadline) {
            disabledBattle = id;
            if (action.consumed) completed = action.request;
            BattleTrace.event("auto_items_paused", Map.of("reason", "server did not confirm an item action"));
            finish(false); return true;
        }
        if (!action.selected && client.screen instanceof PartySelectGUI select) {
            var actor = current.getParticipatingActor(client.player.getUUID());
            var choices = select.getConfig().getPokemon();
            if (actor == null || choices.size() != actor.getPokemon().size() || action.targetIndex >= choices.size()
                    || !actor.getPokemon().get(action.targetIndex).getUuid().equals(action.target)) {
                disabledBattle = id; select.getConfig().getOnCancel().invoke(select); finish(false); return true;
            }
            var choice = choices.get(action.targetIndex);
            var target = actor.getPokemon().get(action.targetIndex);
            String species = choice.getPokemonProperties().getSpecies();
            if (!choice.getEnabled() || species == null || !CobblemonTeamAdvisor.normalizedId(species.replaceFirst("^.*:", "")).equals(
                    CobblemonTeamAdvisor.normalizedId(target.getSpecies().getResourceIdentifier().getPath()))
                    || !Integer.valueOf(target.getLevel()).equals(choice.getPokemonProperties().getLevel())) {
                disabledBattle = id; select.getConfig().getOnCancel().invoke(select); finish(false); return true;
            }
            action.selected = true; action.deadline = System.currentTimeMillis() + 15_000;
            select.getConfig().getOnSelect().invoke(select, choice);
            client.setScreen(action.gui);
            TrainerBattleTracker.automatedAction();
            BattleTrace.event("auto_item_target_selected", Map.of("pokemon", action.target.toString()));
        }
        return true;
    }

    private static void finish(boolean confirmed) {
        Pending action = pending; pending = null;
        if (action == null) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.gameMode != null) {
            var inventory = client.player.getInventory();
            if (action.source >= 9 && client.player.containerMenu == client.player.inventoryMenu
                    && ItemStack.matches(inventory.getItem(action.source), action.previous)
                    && (inventory.getItem(action.hand).isEmpty() || ItemStack.isSameItemSameComponents(inventory.getItem(action.hand), action.medicine)))
                client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, action.source, action.hand, ClickType.SWAP, client.player);
            if (inventory.selected == action.hand) inventory.selected = action.originalHand;
            if (CobblemonClient.INSTANCE.getBattle() != null && (client.screen == null || client.screen instanceof PartySelectGUI)) client.setScreen(action.gui);
        }
        BattleTrace.event("auto_item_finished", Map.of("confirmed", confirmed));
    }
}
