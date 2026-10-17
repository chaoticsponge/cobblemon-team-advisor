package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.client.CobblemonClient;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class RareEncounterGuard {
    private static UUID battle;
    private static final Set<UUID> approved = new HashSet<>();
    private static final Set<UUID> paused = new HashSet<>();

    static int check() {
        var current = CobblemonClient.INSTANCE.getBattle();
        UUID id = current == null ? null : current.getBattleId();
        if (!java.util.Objects.equals(battle, id)) { battle = id; approved.clear(); paused.clear(); }
        if (current == null || current.getSpectating()) return 0;
        boolean waiting = false;
        boolean stop = false;
        for (var side : current.getSides()) for (var actor : side.getActors()) {
            if (actor.getType() != ActorType.WILD) continue;
            for (var active : actor.getActivePokemon()) {
                if (!active.hasPokemon()) { waiting = true; continue; }
                var pokemon = active.getBattlePokemon();
                if (approved.contains(pokemon.getUuid())) continue;
                var dex = CobblemonClient.INSTANCE.getClientPokedexData();
                var progress = dex == null ? null : dex.getHighestKnowledgeForSpecies(pokemon.getSpecies().getResourceIdentifier());
                boolean caught = RareEncounterPolicy.owned(progress == null ? null : progress.name());
                Set<String> labels = pokemon.getSpecies().getLabels();
                String reason = RareEncounterPolicy.reason(Boolean.TRUE.equals(pokemon.getProperties().getShiny()) || pokemon.getState().getCurrentAspects().contains("shiny"),
                        labels.contains("legendary") || labels.contains("mythical") || labels.contains("ultra_beast"), caught, BattleAdvisor.raid(pokemon));
                if (reason != null) {
                    stop = true;
                    if (paused.add(pokemon.getUuid())) {
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("AUTO paused: " + reason + " Pokémon"), true);
                        BattleTrace.event("rare_encounter_pause", java.util.Map.of("pokemon", pokemon.getUuid().toString(), "reason", reason));
                    }
                }
            }
        }
        return stop ? 2 : waiting ? 1 : 0;
    }

    static void allowPausedEncounter() { approved.addAll(paused); }
}
