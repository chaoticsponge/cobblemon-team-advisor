package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.storage.party.PartyPosition;
import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.net.messages.server.storage.SwapPCPartyPokemonPacket;
import com.cobblemon.mod.common.net.messages.server.storage.party.SwapPartyPokemonPacket;
import com.cobblemon.mod.common.net.messages.server.storage.party.MovePartyPokemonPacket;
import com.cobblemon.mod.common.net.messages.server.storage.pc.MovePCPokemonToPartyPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class LoadoutScreen extends Screen {
    private final PCGUI parent;
    private final List<SavedLoadouts.Entry> saved = new ArrayList<>();
    private final Set<String> generated = new HashSet<>();
    private List<String> draft = List.of();
    private EditBox name, cap;
    private Button apply, save;
    private String status = "";
    private boolean writable = true, applying;
    private int page, rows = 1, pendingSlot = -1;
    private UUID pendingId;
    private long deadline;

    LoadoutScreen(PCGUI parent) {
        super(Component.literal("Optimized loadouts")); this.parent = parent;
        try { saved.addAll(SavedLoadouts.read(OptimizedLoadouts.file(parent))); saved.forEach(entry -> generated.add(TeamOptimizer.signature(entry.pokemon()))); }
        catch (java.io.IOException error) { writable = false; status = "Cannot read saved loadouts"; }
    }

    @Override protected void init() {
        int x = width / 2 - 155, y = Math.max(24, height / 2 - 110);
        String previousName = name == null ? "Loadout " + (saved.size() + 1) : name.getValue();
        String previousCap = cap == null ? "100" : cap.getValue();
        name = new EditBox(font, x, y, 178, 20, Component.literal("Name"));
        name.setMaxLength(40); name.setValue(previousName); addRenderableWidget(name);
        cap = new EditBox(font, x + 186, y, 42, 20, Component.literal("Level cap"));
        cap.setMaxLength(3); cap.setFilter(text -> text.matches("[0-9]*")); cap.setValue(previousCap);
        cap.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Level cap"))); addRenderableWidget(cap);
        addRenderableWidget(Button.builder(Component.literal("Create loadout"), button -> create()).bounds(x, y + 26, 150, 20).build());
        save = addRenderableWidget(Button.builder(Component.literal("Save"), button -> save()).bounds(x + 158, y + 26, 70, 20).build());
        apply = addRenderableWidget(Button.builder(Component.literal("Apply"), button -> { applying = true; pendingSlot = -1; status = "Applying…"; }).bounds(x + 236, y + 26, 74, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose()).bounds(x + 236, y, 74, 20).build());
        rows = Math.max(1, Math.min(3, (height - y - 176) / 22));
        page = Math.min(page, Math.max(0, (saved.size() - 1) / rows));
        for (int i = 0; i < rows; i++) {
            int index = page * rows + i;
            if (index >= saved.size()) break;
            var entry = saved.get(index);
            addRenderableWidget(Button.builder(Component.literal(entry.name()), button -> { if (!applying) { draft = entry.pokemon(); name.setValue(entry.name()); status = ""; } })
                    .bounds(x, y + 176 + i * 22, 228, 20).build());
        }
        if (saved.size() > rows) addRenderableWidget(Button.builder(Component.literal("Next"), button -> { if (!applying) { page = (page + 1) % ((saved.size() + rows - 1) / rows); rebuildWidgets(); } })
                .bounds(x + 236, y + 176, 74, 20).build());
    }

    private void create() {
        if (applying) return;
        int limit;
        try { limit = Integer.parseInt(cap.getValue()); } catch (NumberFormatException error) { status = "Set a level cap"; return; }
        if (limit < 1 || limit > 100) { status = "Level cap: 1–100"; return; }
        List<TeamOptimizer.Candidate> candidates = OptimizedLoadouts.owned(parent).values().stream()
                .filter(pokemon -> pokemon.getLevel() <= limit && !pokemon.getAspects().contains("egg")).map(OptimizedLoadouts::candidate)
                .filter(candidate -> candidate.physical() > 0 || candidate.special() > 0).toList();
        draft = TeamOptimizer.create(candidates, generated, System.nanoTime());
        generated.add(TeamOptimizer.signature(draft));
        name.setValue("Loadout " + (saved.size() + 1));
        status = draft.isEmpty() ? "No eligible Pokémon" : "";
    }

    private void save() {
        if (!writable || applying || draft.isEmpty() || saved.size() >= 100) return;
        String label = name.getValue().strip();
        if (label.isEmpty()) return;
        var entry = new SavedLoadouts.Entry(label, List.copyOf(draft));
        List<SavedLoadouts.Entry> next = new ArrayList<>(saved); next.add(entry);
        try {
            SavedLoadouts.save(OptimizedLoadouts.file(parent), next);
            saved.add(entry); page = (saved.size() - 1) / rows; status = "Saved"; rebuildWidgets();
        } catch (java.io.IOException error) { status = "Could not save loadout"; }
    }

    @Override public void tick() {
        super.tick();
        save.active = writable && !applying && !draft.isEmpty() && saved.size() < 100;
        apply.active = !applying && !draft.isEmpty();
        if (!applying) return;
        if (com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null) { stop("Cannot apply during battle"); return; }
        if (pendingSlot >= 0) {
            Pokemon actual = parent.getParty().get(pendingSlot);
            if (pendingId == null ? actual == null : actual != null && actual.getUuid().equals(pendingId)) { pendingSlot = -1; }
            else { if (System.currentTimeMillis() > deadline) stop("Swap not confirmed"); return; }
        }
        var owned = OptimizedLoadouts.owned(parent);
        if (draft.stream().anyMatch(id -> !owned.containsKey(id))) { stop("A Pokémon is missing"); return; }
        for (int slot = 0; slot < draft.size(); slot++) {
            UUID wanted = UUID.fromString(draft.get(slot));
            Pokemon current = parent.getParty().get(slot);
            if (current != null && current.getUuid().equals(wanted)) continue;
            Pokemon incoming = owned.get(wanted.toString());
            int from = -1;
            for (int j = 0; j < parent.getParty().getSlots().size(); j++) {
                Pokemon member = parent.getParty().get(j);
                if (member != null && member.getUuid().equals(wanted)) { from = j; break; }
            }
            if (from >= 0) {
                if (current == null) new MovePartyPokemonPacket(wanted, new PartyPosition(from), new PartyPosition(slot)).sendToServer();
                else new SwapPartyPokemonPacket(wanted, new PartyPosition(from), current.getUuid(), new PartyPosition(slot)).sendToServer();
            } else {
                var position = parent.getPc().getPosition(incoming);
                if (position == null) { stop("A Pokémon is missing"); return; }
                if (current == null) new MovePCPokemonToPartyPacket(wanted, position, new PartyPosition(slot)).sendToServer();
                else new SwapPCPartyPokemonPacket(current.getUuid(), new PartyPosition(slot), wanted, position).sendToServer();
            }
            pendingSlot = slot; pendingId = wanted; deadline = System.currentTimeMillis() + 5000; return;
        }
        for (int slot = draft.size(); slot < parent.getParty().getSlots().size(); slot++) {
            Pokemon extra = parent.getParty().get(slot);
            if (extra == null) continue;
            for (int box = 0; box < parent.getPc().getBoxes().size(); box++) {
                var slots = parent.getPc().getBoxes().get(box).getSlots();
                for (int position = 0; position < slots.size(); position++) if (slots.get(position) == null) {
                    new com.cobblemon.mod.common.net.messages.server.storage.pc.MovePartyPokemonToPCPacket(extra.getUuid(), new PartyPosition(slot),
                            new com.cobblemon.mod.common.api.storage.pc.PCPosition(box, position)).sendToServer();
                    pendingSlot = slot; pendingId = null; deadline = System.currentTimeMillis() + 5000; return;
                }
            }
            stop("PC full"); return;
        }
        stop("Applied");
    }

    private void stop(String message) { applying = false; pendingSlot = -1; status = message; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int x = width / 2 - 155, y = Math.max(24, height / 2 - 110);
        graphics.drawCenteredString(font, title, width / 2, y - 18, 0xFFFFFF);
        var owned = OptimizedLoadouts.owned(parent);
        for (int i = 0; i < draft.size(); i++) {
            Pokemon pokemon = owned.get(draft.get(i));
            String label = pokemon == null ? "Missing Pokémon" : pokemon.getSpecies().getTranslatedName().getString() + "  Lv. " + pokemon.getLevel();
            graphics.drawString(font, (i + 1) + ". " + label, x + 4, y + 56 + i * 17, pokemon == null ? 0xFF7777 : 0xFFFFFF);
        }
        if (!status.isEmpty()) graphics.drawString(font, status, x + 4, y + 160, 0xFFDD88);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() { applying = false; pendingSlot = -1; minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
