package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.pokedex.PokedexLearnedInformation;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.net.messages.client.pokedex.ServerConfirmedRegisterPacket;
import com.cobblemon.mod.common.pokedex.scanner.PokedexUsageContext;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class PokedexScanAdvisor {
    private static final PokedexScanState STATE = new PokedexScanState();
    private static final int VISIBLE_EVOLUTIONS = 4;
    private static EvolutionGuide evolutions;

    private PokedexScanAdvisor() { }

    static void tick() {
        var context = CobblemonClient.INSTANCE.getPokedexUsageContext();
        // Registered Pokémon do not send another registration packet. Wait for a full focus lock instead.
        observe(context, context.getNewPokemonInfo() == PokedexLearnedInformation.NONE
                && context.getFocusIntervals() >= PokedexUsageContext.FOCUS_INTERVALS);
    }

    public static void confirmed(PokedexUsageContext context, ServerConfirmedRegisterPacket packet) {
        if (packet.getSpecies().equals(context.getScannedSpecies())) observe(context, true);
    }

    private static void observe(PokedexUsageContext context, boolean complete) {
        Minecraft minecraft = Minecraft.getInstance();
        var focus = context.getScannableEntityInFocus();
        boolean scanning = minecraft.player != null && context.getScanningGuiOpen();
        if (!STATE.update(focus, scanning, complete)) return;
        var data = focus.resolvePokemonScan();
        if (data == null) return;
        show(data.getApparentSpecies(), data.getApparentForm());
    }

    private static void show(Species species, FormData form) {
        if (evolutions == null) evolutions = EvolutionGuide.load();
        List<String> types = new ArrayList<>();
        for (var type : form.getTypes()) types.add(type.showdownId());
        MutableComponent message = Component.literal("▸ Pokédex · ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                .append(species.getTranslatedName());
        if (!form.getName().equalsIgnoreCase("normal")) message.append(" (" + form.getName() + ")");
        message.append(Component.literal(" · " + types.stream().map(EvolutionGuide::readable).collect(Collectors.joining(" / ")))
                .withStyle(ChatFormatting.WHITE).withStyle(style -> style.withBold(false)));
        row(message, "Weak to", defensive(types, 4, 2), ChatFormatting.RED);
        row(message, "Resists", defensive(types, 0.25, 0.5), ChatFormatting.GREEN);
        row(message, "Immune to", defensive(types, 0), ChatFormatting.AQUA);
        List<String> strong = TypeMatchups.TYPES.stream().filter(defender -> types.stream()
                .anyMatch(attack -> TypeMatchups.multiplier(attack, List.of(defender)) > 1)).map(EvolutionGuide::readable).toList();
        row(message, "Strong vs", strong.isEmpty() ? "None" : String.join(", ", strong), ChatFormatting.GOLD);
        var next = evolutions.next(species.getResourceIdentifier().toString(), form.getName());
        if (!next.known()) row(message, "Evolution", "Requirements unavailable for this species/form", ChatFormatting.GRAY);
        else if (next.entries().isEmpty()) row(message, "Evolution", "No further evolution in the guide", ChatFormatting.GRAY);
        else {
            for (var entry : next.entries().subList(0, Math.min(VISIBLE_EVOLUTIONS, next.entries().size()))) {
                row(message, "→ " + entry.target(), entry.requirements(), ChatFormatting.LIGHT_PURPLE);
            }
            if (next.entries().size() > VISIBLE_EVOLUTIONS) {
                message.append(Component.literal("\n  More evolutions [hover]: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .withStyle(style -> style.withBold(false)));
                for (int start = VISIBLE_EVOLUTIONS; start < next.entries().size(); start += VISIBLE_EVOLUTIONS) {
                    int end = Math.min(start + VISIBLE_EVOLUTIONS, next.entries().size());
                    String more = next.entries().subList(start, end).stream().map(entry -> entry.target() + " — " + entry.requirements())
                            .collect(Collectors.joining("\n\n"));
                    message.append(Component.literal("[" + (start + 1) + "–" + end + "] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                            .withStyle(style -> style.withBold(false).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(more)))));
                }
            }
        }
        // Local chat only: no chat command, server packet or broadcast.
        Minecraft.getInstance().gui.getChat().addMessage(message);
    }

    private static String defensive(List<String> types, double... amounts) {
        List<String> groups = new ArrayList<>();
        for (double amount : amounts) {
            List<String> matches = TypeMatchups.TYPES.stream().filter(type -> TypeMatchups.multiplier(type, types) == amount)
                    .map(EvolutionGuide::readable).toList();
            if (!matches.isEmpty()) groups.add(String.join(", ", matches) + (amount == 0 ? "" : " ×" + (amount == (int) amount ? Integer.toString((int) amount) : Double.toString(amount))));
        }
        return groups.isEmpty() ? "None" : String.join(" · ", groups);
    }

    private static void row(MutableComponent message, String label, String value, ChatFormatting color) {
        message.append(Component.literal("\n  " + label + " │ ").withStyle(color).withStyle(style -> style.withBold(false)))
                .append(Component.literal(value).withStyle(ChatFormatting.GRAY).withStyle(style -> style.withBold(false)));
    }
}
