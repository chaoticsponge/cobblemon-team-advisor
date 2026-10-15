package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.moves.categories.DamageCategories;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

final class OptimizedLoadouts {
    static final List<String> TYPES = List.of("normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
            "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy");

    static Map<String, Pokemon> owned(PCGUI pc) {
        Map<String, Pokemon> result = new LinkedHashMap<>();
        for (Pokemon pokemon : pc.getParty()) if (pokemon != null) result.put(pokemon.getUuid().toString(), pokemon);
        for (var box : pc.getPc().getBoxes()) for (Pokemon pokemon : box) if (pokemon != null) result.put(pokemon.getUuid().toString(), pokemon);
        return result;
    }

    static TeamOptimizer.Candidate candidate(Pokemon pokemon) {
        double[] offense = new double[18], defense = new double[18];
        String ability = CobblemonTeamAdvisor.normalizedId(pokemon.getAbility().getName());
        String item = pokemon.getHeldItem$common().isEmpty() ? "" : CobblemonTeamAdvisor.normalizedId(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(pokemon.getHeldItem$common().getItem()).getPath());
        var user = new BattleDamageModifiers.User(ability, item, "", 1, "");
        var target = new BattleDamageModifiers.User("", "", "", 1, "");
        double physical = 0, special = 0;
        for (var move : pokemon.getMoveSet()) {
            if (move == null || move.getDamageCategory() == DamageCategories.INSTANCE.getSTATUS()) continue;
            var type = move.getTemplate().getEffectiveElementalType(pokemon);
            boolean specialMove = move.getDamageCategory() == DamageCategories.INSTANCE.getSPECIAL();
            double power = Math.max(0, move.getTemplate().getPower());
            if (power <= 0) power = 50; // Variable/fixed power is a rough coverage signal, not a KO forecast.
            String effectiveType = BattleDamageModifiers.changesType(move.getName()) ? BattleDamageModifiers.type(type.showdownId(), ability) : type.showdownId();
            var info = new BattleDamageModifiers.Move(move.getName(), effectiveType, type.showdownId(), power, specialMove, BattleAdvisor.moveFlags(move.getTemplate()));
            power = BattleDamageModifiers.power(user, target, info);
            power *= BattleDamageModifiers.powerFactor(user, new BattleDamageModifiers.Move(info.id(), info.type(), info.originalType(), power, specialMove, info.flags()));
            if (info.has("charging") || info.has("recharge")) power /= 2;
            double attack = pokemon.getStat(specialMove ? Stats.SPECIAL_ATTACK : Stats.ATTACK) * BattleDamageModifiers.attackFactor(user, info);
            if (!specialMove && (ability.equals("hugepower") || ability.equals("purepower"))) attack *= 2;
            if (specialMove && item.endsWith("choicespecs") || !specialMove && item.endsWith("choiceband")) attack *= 1.5;
            double stab = 1;
            for (var ownType : pokemon.getTypes()) if (ownType.showdownId().equals(effectiveType)) stab = ability.equals("adaptability") ? 2 : 1.5;
            double accuracy = move.getTemplate().getAccuracy() <= 0 ? 1 : move.getTemplate().getAccuracy() / 100.0;
            double strength = power * attack * stab * accuracy / 100;
            if (specialMove) special = Math.max(special, strength); else physical = Math.max(physical, strength);
            for (int i = 0; i < TYPES.size(); i++) offense[i] = Math.max(offense[i], strength * TypeMatchups.multiplier(effectiveType, List.of(TYPES.get(i))));
        }
        List<String> types = new ArrayList<>();
        pokemon.getTypes().forEach(type -> types.add(type.showdownId()));
        for (int i = 0; i < TYPES.size(); i++) defense[i] = BattleMatchupPolicy.effectiveMultiplier(
                TypeMatchups.multiplier(TYPES.get(i), types), TYPES.get(i), ability, "");
        double bulk = Math.sqrt(pokemon.getMaxHealth() * (pokemon.getStat(Stats.DEFENCE) + pokemon.getStat(Stats.SPECIAL_DEFENCE)) / 2.0);
        double strength = Math.max(physical, special) * 0.4 + bulk * 0.3 + pokemon.getStat(Stats.SPEED) * 0.15;
        return new TeamOptimizer.Candidate(pokemon.getUuid().toString(), pokemon.getSpecies().getResourceIdentifier().toString(), strength,
                pokemon.getStat(Stats.SPEED), physical, special, bulk, offense, defense);
    }

    static Path file(PCGUI pc) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("optimized-loadouts").resolve(pc.getPc().getUuid() + ".json");
    }

}
