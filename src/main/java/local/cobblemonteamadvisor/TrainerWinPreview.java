package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityAttachment;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

final class TrainerWinPreview {
    private static final Map<UUID, String> LABELS = new HashMap<>();
    private static final Map<String, String> CACHE = new HashMap<>();
    private static final Set<Class<?>> FAILED_IDENTITIES = new java.util.HashSet<>();
    private static Object level;
    private static String partyKey = "";
    private static int ticks;

    private TrainerWinPreview() { }

    static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != level) {
            level = client.level;
            LABELS.clear(); CACHE.clear(); partyKey = "";
        }
        if (client.player == null || client.level == null || CobblemonClient.INSTANCE.getBattle() != null || ++ticks % 10 != 0) return;
        var storage = CobblemonClient.INSTANCE.getStorage();
        if (storage.getParty() == null) return;
        List<Pokemon> party = new ArrayList<>();
        for (Pokemon pokemon : storage.getParty()) if (pokemon != null) party.add(pokemon);
        if (party.isEmpty()) { LABELS.clear(); return; }
        int selectedSlot = storage.getSelectedSlot();
        Pokemon selectedPokemon = selectedSlot >= 0 && selectedSlot < storage.getParty().getSlots().size()
                ? storage.getParty().get(selectedSlot) : null;
        UUID selected = selectedPokemon == null ? null : selectedPokemon.getUuid();
        StringBuilder key = new StringBuilder().append(selected);
        for (Pokemon pokemon : party) {
            key.append('|').append(pokemon.getUuid()).append(':').append(pokemon.getCurrentHealth()).append(':').append(pokemon.getMaxHealth())
                    .append(':').append(pokemon.getLevel()).append(':').append(pokemon.getAspects())
                    .append(':').append(pokemon.getAbility().getName()).append(':').append(pokemon.getNature().getName())
                    .append(':').append(pokemon.getHeldItem$common()).append(':').append(pokemon.getStatus());
            for (Stats stat : Stats.values()) if (stat != Stats.ACCURACY && stat != Stats.EVASION) key.append(':').append(pokemon.getStat(stat));
            for (var move : pokemon.getMoveSet()) if (move != null) key.append(':').append(move.getName()).append('/').append(move.getCurrentPp());
        }
        if (!partyKey.contentEquals(key)) { partyKey = key.toString(); CACHE.clear(); LABELS.clear(); }
        List<LivingEntity> nearby = client.level.getEntitiesOfClass(LivingEntity.class, client.player.getBoundingBox().inflate(24),
                entity -> trainer(entity) && !entity.isInvisible() && client.player.hasLineOfSight(entity));
        nearby.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(client.player)));
        Set<UUID> visible = nearby.stream().map(LivingEntity::getUUID).collect(java.util.stream.Collectors.toSet());
        LABELS.keySet().retainAll(visible);
        // At most one new team simulation per half-second, never during rendering.
        boolean calculated = false;
        for (LivingEntity entity : nearby) {
            String id = trainerId(entity);
            String name = entity.getName().getString();
            String identity = id == null ? "name:" + name : "id:" + id;
            String label = CACHE.get(identity);
            if (label == null) {
                if (calculated) continue;
                calculated = true;
                List<TrainerRoster.Member> team = TrainerRoster.preview(name, id);
                try {
                    label = team.isEmpty() ? "Est. win: team unknown" : estimate(party, team, selected);
                } catch (RuntimeException error) {
                    LogUtils.getLogger().warn("Could not preview trainer {}", identity, error);
                    label = "Est. win: team unknown";
                }
                if (CACHE.size() >= 64) CACHE.clear();
                CACHE.put(identity, label);
                LogUtils.getLogger().debug("Trainer preview {}: {}", identity, label);
            }
            LABELS.put(entity.getUUID(), label);
        }
    }

    private static boolean rctTrainer(LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("rctmod:trainer");
    }

    private static boolean trainer(LivingEntity entity) {
        return rctTrainer(entity) || entity instanceof NPCEntity && !TrainerRoster.preview(entity.getName().getString(), null).isEmpty();
    }

    private static String trainerId(LivingEntity entity) {
        if (!rctTrainer(entity)) return null;
        try {
            // Resolve against the already-loaded entity; optional mods can use a different class loader.
            return (String) entity.getClass().getMethod("getTrainerId").invoke(entity);
        } catch (ReflectiveOperationException error) {
            if (FAILED_IDENTITIES.add(entity.getClass())) LogUtils.getLogger().warn("Could not read nearby trainer identity", error);
            return "unknown";
        }
    }

    private static String estimate(List<Pokemon> party, List<TrainerRoster.Member> team, UUID selected) {
        List<Pokemon> foes = new ArrayList<>();
        for (TrainerRoster.Member member : team) {
            if (PokemonSpecies.getByName(member.species()) == null || member.moves().isEmpty()
                    || member.moves().stream().anyMatch(move -> Moves.getByName(move) == null)) return "Est. win: team unknown";
            Pokemon pokemon = new Pokemon();
            pokemon.setClient$common(true);
            PokemonProperties properties = new PokemonProperties();
            properties.setSpecies(member.species()); properties.setLevel(member.level());
            properties.setNature(member.nature()); properties.setAbility(member.ability());
            properties.setAspects(member.aspects() == null ? Set.of() : Set.copyOf(member.aspects()));
            if (member.heldItem() != null) properties.setHeldItem(member.heldItem().contains(":")
                    ? member.heldItem() : "cobblemon:" + member.heldItem());
            properties.apply(pokemon);
            pokemon.getMoveSet().clear();
            for (String move : member.moves()) pokemon.getMoveSet().add(Moves.getByName(move).create());
            for (Stats stat : Stats.values()) if (stat != Stats.ACCURACY && stat != Stats.EVASION) {
                pokemon.setIV(stat, member.ivs() == null ? 31 : member.ivs().getOrDefault(stat.getShowdownId(), 31));
                pokemon.setEV(stat, member.evs() == null ? 0 : member.evs().getOrDefault(stat.getShowdownId(), 0));
            }
            pokemon.setCurrentHealth(pokemon.getMaxHealth());
            foes.add(pokemon);
        }
        int lead = 0;
        for (int i = 0; i < party.size(); i++) if (party.get(i).getUuid().equals(selected)) lead = i;
        return TrainerWinEstimator.estimate(BattleAdvisor.previewModel(party, foes, lead)).label();
    }

    static void render(RenderLivingEvent.Post<?, ?> event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui || CobblemonClient.INSTANCE.getBattle() != null
                || event.getEntity().isInvisible()
                || event.getEntity().distanceToSqr(client.player) > 24 * 24) return;
        String label = LABELS.get(event.getEntity().getUUID());
        if (label == null) return;
        var pose = event.getPoseStack();
        pose.pushPose();
        try {
            var entity = event.getEntity();
            var anchor = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(event.getPartialTick()));
            if (anchor == null) pose.translate(0, entity.getBbHeight() + 0.85, 0);
            else pose.translate(anchor.x, anchor.y + 0.85, anchor.z);
            pose.mulPose(client.getEntityRenderDispatcher().cameraOrientation());
            pose.scale(0.025F, -0.025F, 0.025F);
            client.font.drawInBatch(label, -client.font.width(label) / 2.0F, 0, 0xFFFFFFAA, false,
                    pose.last().pose(), event.getMultiBufferSource(), Font.DisplayMode.NORMAL, 0x60000000, event.getPackedLight());
        } finally {
            pose.popPose();
        }
    }
}
