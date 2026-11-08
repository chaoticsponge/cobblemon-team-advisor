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
    private static TrainerBattleStats stats, allStats;
    private static String version;
    private static final java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
    static java.util.Map<String, String> environment() { return java.util.Map.copyOf(environment); }
    private static ClientBattle trackedBattle;

    private TrainerBattleTracker() { }

    static void initialize() {
        version = ModList.get().getModContainerById(CobblemonTeamAdvisor.MOD_ID).orElseThrow()
                .getModInfo().getVersion().toString();
        for (String id : List.of("cobblemon", "rctmod", "mega_showdown", CobblemonTeamAdvisor.MOD_ID))
            ModList.get().getModContainerById(id).ifPresent(mod -> environment.put(id, mod.getModInfo().getVersion().toString()));
        BattleTrace.initialize(Minecraft.getInstance().gameDirectory.toPath(), version);
        var profile = Minecraft.getInstance().gameDirectory.toPath();
        try { stats = new TrainerBattleStats(profile); }
        catch (IOException error) { LogUtils.getLogger().error("Could not initialize AUTO trainer statistics", error); }
        try { allStats = new TrainerBattleStats(profile, "trainer-all-battle-stats", false); }
        catch (IOException error) { LogUtils.getLogger().error("Could not initialize all-trainer statistics", error); }
    }

    static void sync(ClientBattle battle) {
        if (stats == null && allStats == null || battle != null && battle == trackedBattle) return;
        Minecraft client = Minecraft.getInstance();
        ClientBattleActor player = battle == null || client.player == null || battle.getSpectating()
                ? null : battle.getParticipatingActor(client.player.getUUID());
        if (player == null) {
            trackedBattle = null;
            for (var table : Arrays.asList(stats, allStats)) if (table != null) table.start(null, version, null, null, null);
            return;
        }
        List<ClientBattleActor> allies = player.getSide().getActors();
        List<ClientBattleActor> enemies = Arrays.stream(battle.getSides())
                .filter(side -> side != player.getSide()).flatMap(side -> side.getActors().stream()).toList();
        if (enemies.isEmpty()) return;
        trackedBattle = battle;
        boolean trainers = enemies.stream().allMatch(actor -> actor.getType() == ActorType.NPC);
        for (var table : Arrays.asList(stats, allStats)) if (table != null)
            table.start(battle.getBattleId(), version, trainers ? names(enemies) : null, names(allies), names(enemies));
    }

    private static String names(List<ClientBattleActor> actors) {
        return actors.stream().map(actor -> actor.getDisplayName().getString()).collect(Collectors.joining(" & "));
    }

    static void automatedAction() {
        sync(CobblemonClient.INSTANCE.getBattle());
        for (var table : Arrays.asList(stats, allStats)) if (table != null) table.automatedAction();
    }

    static void message(String key, Object[] arguments) {
        if (arguments.length != 1) return;
        String names = arguments[0] instanceof Component component ? component.getString() : String.valueOf(arguments[0]);
        for (var table : Arrays.asList(stats, allStats)) if (table != null) try { table.message(key, names); }
        catch (IOException error) { LogUtils.getLogger().error("Could not save local trainer battle statistics", error); }
    }
}
