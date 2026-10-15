package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattleActor;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

final class TrainerBattleTracker {
    private static TrainerBattleStats stats;
    private static String version;
    private static ClientBattle trackedBattle;

    private TrainerBattleTracker() { }

    static void initialize() {
        version = ModList.get().getModContainerById(CobblemonTeamAdvisor.MOD_ID).orElseThrow()
                .getModInfo().getVersion().toString();
        try {
            stats = new TrainerBattleStats(Minecraft.getInstance().gameDirectory.toPath());
        } catch (IOException error) {
            LogUtils.getLogger().error("Could not initialize local trainer battle statistics", error);
        }
    }

    static void sync(ClientBattle battle) {
        if (stats == null || battle != null && battle == trackedBattle) return;
        Minecraft client = Minecraft.getInstance();
        ClientBattleActor player = battle == null || client.player == null || battle.getSpectating()
                ? null : battle.getParticipatingActor(client.player.getUUID());
        if (player == null) {
            trackedBattle = null;
            stats.start(null, version, null, null, null);
            return;
        }
        List<ClientBattleActor> allies = player.getSide().getActors();
        List<ClientBattleActor> enemies = Arrays.stream(battle.getSides())
                .filter(side -> side != player.getSide()).flatMap(side -> side.getActors().stream()).toList();
        if (enemies.isEmpty()) return;
        trackedBattle = battle;
        boolean trainers = enemies.stream().allMatch(actor -> actor.getType() == ActorType.NPC);
        stats.start(battle.getBattleId(), version, trainers ? names(enemies) : null, names(allies), names(enemies));
    }

    private static String names(List<ClientBattleActor> actors) {
        return actors.stream().map(actor -> actor.getDisplayName().getString()).collect(Collectors.joining(" & "));
    }

    static void automatedAction() {
        sync(CobblemonClient.INSTANCE.getBattle());
        if (stats != null) stats.automatedAction();
    }

    static void message(String key, Object[] arguments) {
        if (stats == null || arguments.length != 1) return;
        String names = arguments[0] instanceof Component component ? component.getString() : String.valueOf(arguments[0]);
        try {
            stats.message(key, names);
        } catch (IOException error) {
            LogUtils.getLogger().error("Could not save local trainer battle statistics", error);
        }
    }
}
