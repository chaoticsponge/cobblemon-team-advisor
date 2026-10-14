package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.moves.categories.DamageCategories;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.battles.MoveTarget;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.ShowdownPokemon;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BattleAdvisor {
    private static final JsonObject EFFECTS = loadEffects();
    private static final JsonObject NO_EFFECT = new JsonObject();
    private static final Map<BattleMoveSelection.MoveTile, MoveAdvice> MOVE_CACHE = new HashMap<>();
    private static final Map<MatchupKey, Matchup> MATCHUP_CACHE = new HashMap<>();
    private record MatchupKey(UUID pokemon, UUID opponent, boolean active, String condition) { }
    private static UUID battleId;
    private static BattleFieldState field = new BattleFieldState();

    private BattleAdvisor() { }

    static void syncBattle() {
        // Reuse identical decisions within a tick; packets also invalidate the caches.
        MOVE_CACHE.clear();
        MATCHUP_CACHE.clear();
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        UUID id = battle == null ? null : battle.getBattleId();
        if (!java.util.Objects.equals(id, battleId)) {
            battleId = id;
            field = new BattleFieldState();
        }
    }

    public static void observeMessages(List<Component> messages) {
        syncBattle();
        if (battleId == null) return;
        for (Component message : messages) observe(message);
    }

    private static void observe(Component message) {
        if (message.getContents() instanceof TranslatableContents translation) {
            field.observe(translation.getKey());
            for (Object argument : translation.getArgs()) {
                if (argument instanceof Component component) observe(component);
            }
        }
        for (Component sibling : message.getSiblings()) observe(sibling);
    }

    record Matchup(int score, boolean survives, double incoming, double entry, String reason) { }
    record MoveAdvice(double score, boolean ko, boolean firstKo, String reason) { }
    private record Fighter(Pokemon own, ClientBattlePokemon live, List<ElementalType> types, String ability,
                           String item, String status, double hp, double maxHp, boolean allied, boolean entering) {
        int stage(Stats stat) {
            return live == null || entering ? 0 : live.getStatChanges().getOrDefault(stat, 0);
        }

        double stat(Stats stat) {
            double value;
            if (own != null) {
                value = own.getStat(stat);
            } else {
                int base = live.getSpecies().getForm(live.getProperties().getAspects()).getBaseStats().getOrDefault(stat, 1);
                int iv = live.getProperties().getIvs() == null ? 31 : live.getProperties().getIvs().getOrDefault(stat);
                int ev = live.getProperties().getEvs() == null ? 0 : live.getProperties().getEvs().getOrDefault(stat);
                value = Math.floor((2 * base + iv + ev / 4) * live.getLevel() / 100.0D) + 5;
                if (live.getProperties().getNature() != null) {
                    var nature = Natures.getNature(live.getProperties().getNature());
                    if (nature != null) value = nature.modifyStat(stat, (int) value);
                }
            }
            value *= BattleMatchupPolicy.stageMultiplier(stage(stat));
            if (stat == Stats.ATTACK && status.equals("brn") && !ability.equals("guts")) value *= 0.5D;
            if (stat == Stats.ATTACK && (ability.equals("hugepower") || ability.equals("purepower"))) value *= 2;
            if (stat == Stats.ATTACK && ability.equals("guts") && !status.isEmpty()) value *= 1.5D;
            if (stat == Stats.SPEED) {
                if (status.equals("par") && !ability.equals("quickfeet")) value *= 0.5D;
                if (ability.equals("quickfeet") && !status.isEmpty()) value *= 1.5D;
                if (item.endsWith("choicescarf")) value *= 1.5D;
                if ((allied ? field.ally : field.opponent).tailwind) value *= 2;
                if (entering && grounded(this) && field.ally.web && !boots(this)) value *= 2.0D / 3;
            }
            if (stat == Stats.ATTACK && item.endsWith("choiceband")) value *= 1.5D;
            if (stat == Stats.SPECIAL_ATTACK && item.endsWith("choicespecs")) value *= 1.5D;
            return Math.max(1, value);
        }

        int level() { return own != null ? own.getLevel() : live.getLevel(); }
    }

    private static Fighter mine(Pokemon pokemon, ShowdownPokemon showdown, boolean active) {
        ClientBattlePokemon live = active ? livePokemon(pokemon.getUuid()) : null;
        double max = Math.max(1, pokemon.getMaxHealth());
        double hp = pokemon.getCurrentHealth();
        String status = pokemon.getStatus() == null || pokemon.getStatus().getStatus() == null ? ""
                : pokemon.getStatus().getStatus().getShowdownName();
        if (live != null) {
            hp = live.isHpFlat() ? live.getHpValue() : hpFraction(live) * max;
            status = live.getStatus() == null ? "" : live.getStatus().getShowdownName();
        } else if (showdown != null) {
            String[] condition = showdown.getCondition().split(" ");
            String[] health = condition[0].split("/");
            try {
                if (health.length == 2 && Double.parseDouble(health[1]) > 0) hp = Double.parseDouble(health[0]) / Double.parseDouble(health[1]) * max;
                else if (condition[0].equals("0")) hp = 0;
            } catch (NumberFormatException ignored) { /* Retain synchronized party HP. */ }
            status = condition.length > 1 && !condition[1].equals("fnt") ? condition[1] : "";
        }
        String ability = id(pokemon.getAbility().getName());
        if (showdown != null && showdown.getAbility() != null) ability = id(showdown.getAbility());
        List<ElementalType> types = new ArrayList<>();
        for (ElementalType type : pokemon.getTypes()) types.add(type);
        return new Fighter(pokemon, live, types, ability,
                id(BuiltInRegistries.ITEM.getKey(pokemon.getHeldItem$common().getItem()).getPath()), status,
                Math.max(0, Math.min(max, hp)), max, true, !active);
    }

    private static Fighter enemy(ClientBattlePokemon pokemon) {
        var form = pokemon.getSpecies().getForm(pokemon.getProperties().getAspects());
        List<ElementalType> types = new ArrayList<>();
        for (ElementalType type : form.getTypes()) types.add(type);
        int iv = pokemon.getProperties().getIvs() == null ? 31 : pokemon.getProperties().getIvs().getOrDefault(Stats.HP);
        int ev = pokemon.getProperties().getEvs() == null ? 0 : pokemon.getProperties().getEvs().getOrDefault(Stats.HP);
        double max = Math.floor((2 * form.getBaseStats().getOrDefault(Stats.HP, 1) + iv + ev / 4)
                * pokemon.getLevel() / 100.0D) + pokemon.getLevel() + 10;
        if (pokemon.getSpecies().getName().equalsIgnoreCase("shedinja")) max = 1;
        if (pokemon.isHpFlat() && pokemon.getMaxHp() > 0) max = pokemon.getMaxHp();
        return new Fighter(null, pokemon, types, id(pokemon.getProperties().getAbility()),
                id(pokemon.getProperties().getHeldItem()), pokemon.getStatus() == null ? "" : pokemon.getStatus().getShowdownName(),
                hpFraction(pokemon) * max, max, false, false);
    }

    private static double hpFraction(ClientBattlePokemon pokemon) {
        return pokemon.getMaxHp() <= 0 ? 1 : Math.max(0, Math.min(1, pokemon.getHpValue() / pokemon.getMaxHp()));
    }

    private static ClientBattlePokemon livePokemon(UUID id) {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle != null) {
            for (var side : battle.getSides()) for (var actor : side.getActors()) for (var active : actor.getActivePokemon()) {
                if (active.hasPokemon() && active.getBattlePokemon().getUuid().equals(id)) return active.getBattlePokemon();
            }
        }
        return null;
    }

    private static List<MoveTemplate> enemyMoves(ClientBattlePokemon pokemon) {
        List<MoveTemplate> moves = new ArrayList<>();
        for (String name : CobblemonTeamAdvisor.opponentMoves(pokemon).names()) {
            MoveTemplate move = Moves.getByName(name);
            if (move != null) moves.add(move);
        }
        return moves;
    }

    static Matchup matchup(Pokemon pokemon, ShowdownPokemon showdown, ClientBattlePokemon opponent, boolean active) {
        MatchupKey key = new MatchupKey(pokemon.getUuid(), opponent.getUuid(), active,
                showdown == null ? "" : showdown.getCondition());
        return MATCHUP_CACHE.computeIfAbsent(key, ignored -> calculateMatchup(pokemon, showdown, opponent, active));
    }

    private static Matchup calculateMatchup(Pokemon pokemon, ShowdownPokemon showdown, ClientBattlePokemon opponent, boolean active) {
        Fighter own = mine(pokemon, showdown, active);
        Fighter foe = enemy(opponent);
        double incoming = 0;
        double worstType = 0;
        String threat = "";
        boolean hasDamagingMove = false;
        List<MoveTemplate> moves = enemyMoves(opponent);
        for (MoveTemplate move : moves) {
            if (!damaging(move)) continue;
            hasDamagingMove = true;
            double damage = damage(foe, own, move, move.getElementalType(), 1.0D);
            worstType = Math.max(worstType, multiplier(foe, own, move.getElementalType()));
            if (damage > incoming) {
                incoming = damage;
                threat = move.getElementalType().getName();
            }
        }
        if (moves.isEmpty()) {
            worstType = 1; // Unknown data must not be reported as immunity.
            incoming = own.maxHp * 0.25D;
        }
        double entry = active ? 0 : entryDamage(own);
        double residual = residual(own);
        boolean entryPoison = !active && grounded(own) && field.ally.toxicSpikes > 0 && !boots(own)
                && own.status.isEmpty() && !hasType(own, "poison") && !hasType(own, "steel")
                && !List.of("immunity", "purifyingsalt", "comatose", "poisonheal", "magicguard").contains(own.ability);
        if (entryPoison) residual = Math.max(residual, own.maxHp / 8);
        int defence = -BattleMatchupPolicy.threatScore(worstType);
        double bestDamage = 0;
        for (Move move : pokemon.getMoveSet()) {
            if (move != null && move.getCurrentPp() > 0 && damaging(move.getTemplate())) {
                bestDamage = Math.max(bestDamage, damage(own, foe, move.getTemplate(),
                        move.getTemplate().getEffectiveElementalType(pokemon), 0.85D));
            }
        }
        int score = defence + BattleMatchupPolicy.survivalScore(own.hp, own.maxHp, entry, incoming, residual)
                + (int) Math.round(Math.min(2, bestDamage / Math.max(1, foe.hp)) * 2);
        if (bestDamage == 0) score -= 30;
        if (entryPoison) score -= 4;
        boolean survives = own.hp > entry + incoming + residual;
        String reason = own.hp <= entry ? "entry hazards would KO" : !survives ? "risks KO on next hit"
                : !hasDamagingMove && !moves.isEmpty() ? "no damaging moves in known set"
                : worstType == 0 ? "immune to damaging moves" : worstType < 1 ? "resists enemy coverage" : "survives next hit";
        if (!threat.isEmpty() && worstType >= 1) reason += " (" + threat + ")";
        if (entry > 0) reason += "; entry cost " + Math.round(100 * entry / own.maxHp) + "%";
        return new Matchup(score, survives, incoming, entry, reason);
    }

    static boolean shouldSwitch(BattleMoveSelection selection, Pokemon candidate, ShowdownPokemon showdown, ClientBattlePokemon opponent) {
        Pokemon active = CobblemonTeamAdvisor.activePokemon(selection);
        if (active == null || opponent == null || candidate == null || selection.getMoveSet().getTrapped()) return false;
        for (var tile : selection.getMoveTiles()) {
            if (tile.getSelectable() && moveAdvice(tile).firstKo()) return false;
        }
        Matchup current = matchup(active, null, opponent, true);
        Matchup next = matchup(candidate, showdown, opponent, false);
        if (!next.survives()) return false;
        Fighter own = mine(active, null, true);
        int lostBoosts = 0;
        for (Stats stat : List.of(Stats.ATTACK, Stats.SPECIAL_ATTACK, Stats.DEFENCE, Stats.SPECIAL_DEFENCE, Stats.SPEED)) {
            lostBoosts += Math.max(0, own.stage(stat));
        }
        int cost = current.survives() ? 2 + Math.min(8, lostBoosts * 2) : 0;
        return BattleMatchupPolicy.shouldSwitch(current.score() + cost, next.score());
    }

    static MoveAdvice moveAdvice(BattleMoveSelection.MoveTile tile) {
        return MOVE_CACHE.computeIfAbsent(tile, BattleAdvisor::calculateMoveAdvice);
    }

    private static MoveAdvice calculateMoveAdvice(BattleMoveSelection.MoveTile tile) {
        Pokemon pokemon = tile.getPokemon();
        MoveTemplate move = tile.getMoveTemplate();
        var target = CobblemonTeamAdvisor.target(tile);
        if (pokemon == null || move == null || target == null || !target.hasPokemon()) {
            return new MoveAdvice(0, false, false, "legal fallback");
        }
        Fighter own = mine(pokemon, null, true);
        Fighter foe = enemy(target.getBattlePokemon());
        double accuracy = BattleMatchupPolicy.accuracy(move.getAccuracy(), own.stage(Stats.ACCURACY), foe.stage(Stats.EVASION));
        List<MoveTemplate> replies = enemyMoves(foe.live);
        double incoming = 0;
        double before = 0;
        boolean first = true;
        for (MoveTemplate reply : replies) {
            boolean precedes = BattleMatchupPolicy.actsFirst(move.getPriority(), own.stat(Stats.SPEED),
                    reply.getPriority(), foe.stat(Stats.SPEED), field.trickRoom);
            if (!precedes) first = false;
            double hit = damage(foe, own, reply, reply.getElementalType(), 1.0D);
            incoming = Math.max(incoming, hit);
            if (!precedes) before = Math.max(before, hit);
        }
        if (replies.isEmpty()) { incoming = own.maxHp * 0.25D; before = incoming; first = false; }
        if (!damaging(move)) return utility(own, foe, move, accuracy, incoming, before);
        double damage = damage(own, foe, move, tile.getElementalType(), 0.925D);
        double minimum = damage(own, foe, move, tile.getElementalType(), 0.85D);
        boolean ko = minimum >= foe.hp && foe.hp > 0;
        boolean firstKo = ko && accuracy >= 0.99D && first && !own.status.equals("slp") && !own.status.equals("frz") && !own.status.equals("par");
        JsonObject effect = effects(move);
        double recoil = Math.min(foe.hp, damage) * fraction(effect, "recoil");
        if (own.ability.equals("rockhead") || own.ability.equals("magicguard")) recoil = 0;
        if (own.item.endsWith("lifeorb") && !own.ability.equals("magicguard") && !own.ability.equals("sheerforce")) recoil += own.maxHp / 10;
        if (effect.has("selfdestruct")) recoil = own.hp;
        if (id(move.getName()).equals("struggle")) recoil = own.maxHp / 4;
        double drain = Math.min(own.maxHp - own.hp, Math.min(foe.hp, damage) * fraction(effect, "drain"));
        if (foe.ability.equals("liquidooze")) { recoil += drain; drain = 0; }
        double score = BattleMatchupPolicy.attackScore(damage / Math.max(1, foe.hp), accuracy, ko, firstKo,
                drain / own.maxHp, recoil / own.maxHp, before >= own.hp, recoil >= own.hp);
        if ((id(move.getName()).equals("rapidspin") || id(move.getName()).equals("mortalspin"))
                && damage > 0 && hasHazards()) score += 0.8D;
        String reason = firstKo ? "estimated KO before enemy moves" : ko ? "estimated KO; check turn order / accuracy"
                : "expected damage " + Math.round(100 * damage / foe.maxHp * accuracy) + "%";
        if (recoil > 0) reason += "; recoil " + Math.round(100 * recoil / own.maxHp) + "%";
        if (drain > 0) reason += "; heals " + Math.round(100 * drain / own.maxHp) + "%";
        return new MoveAdvice(score, ko, firstKo, reason);
    }

    private static MoveAdvice utility(Fighter own, Fighter foe, MoveTemplate move, double accuracy, double incoming, double before) {
        JsonObject effect = effects(move);
        String name = id(move.getName());
        double residual = residual(own);
        if (before >= own.hp) return new MoveAdvice(-6, false, false, "may faint before acting");
        double healing = fraction(effect, "heal") * own.maxHp;
        if (name.equals("rest") && !own.status.equals("slp") && own.hp < own.maxHp * 0.4D) healing = own.maxHp;
        if (name.equals("wish") && own.hp > incoming * 2 + residual * 2) healing = own.maxHp * 0.5D;
        if (healing > 0) {
            healing = Math.min(healing, own.maxHp - own.hp);
            double score = BattleMatchupPolicy.healingScore(own.hp, own.maxHp, healing, incoming, residual);
            if (name.equals("rest")) score -= 0.7D;
            if (name.equals("rest") && own.hp - incoming + healing <= incoming * 2 + residual * 2) score = -2;
            return new MoveAdvice(score, false, false, "heal " + Math.round(100 * healing / own.maxHp) + "%");
        }
        if (effect.has("status") || name.equals("yawn")) {
            String status = effect.has("status") ? effect.get("status").getAsString() : "slp";
            if (!foe.status.isEmpty() || statusBlocked(foe, status, effect)
                    || name.equals("thunderwave") && hasType(foe, "ground")) {
                return new MoveAdvice(-1, false, false, "status would fail");
            }
            double value = switch (status) {
                case "slp" -> 1.4D;
                case "par" -> own.stat(Stats.SPEED) <= foe.stat(Stats.SPEED) ? 1.1D : 0.65D;
                case "brn" -> foe.stat(Stats.ATTACK) >= foe.stat(Stats.SPECIAL_ATTACK) ? 1.1D : 0.6D;
                case "tox" -> 0.9D;
                default -> 0.65D;
            };
            if (own.hp <= incoming + residual) value -= 2;
            return new MoveAdvice(value * accuracy, false, false, "apply " + status + " (" + Math.round(accuracy * 100) + "% accurate)");
        }
        JsonObject boosts = effect.has("boosts") && move.getTarget() == MoveTarget.self
                ? effect.getAsJsonObject("boosts") : effect.has("selfBoosts") ? effect.getAsJsonObject("selfBoosts") : null;
        if (boosts != null) {
            double gain = 0;
            for (var boost : boosts.entrySet()) {
                Stats stat = statId(boost.getKey());
                if (stat == null || boost.getValue().getAsInt() <= 0 || own.stage(stat) >= 6) continue;
                if ((stat == Stats.ATTACK || stat == Stats.SPECIAL_ATTACK) && !hasAttackCategory(own, stat)) continue;
                gain += Math.min(boost.getValue().getAsInt(), 6 - own.stage(stat)) * 0.3D;
            }
            boolean safe = own.hp > incoming * 2 + residual * 2;
            return new MoveAdvice(safe ? Math.min(1.0D, gain) : -2, false, false,
                    safe ? "setup while surviving two hits" : "setup is unsafe");
        }
        if (effect.has("sideCondition")) {
            String hazard = effect.get("sideCondition").getAsString();
            boolean present = switch (hazard) {
                case "stealthrock" -> field.opponent.rocks;
                case "spikes" -> field.opponent.spikes >= 3;
                case "toxicspikes" -> field.opponent.toxicSpikes >= 2;
                case "stickyweb" -> field.opponent.web;
                default -> true;
            };
            int bench = foe.live.getActor().getPokemon().size() - 1;
            return new MoveAdvice(!present && bench > 0 && own.hp > incoming * 2 ? 0.7D : -0.5D,
                    false, false, "entry hazard for remaining opponents");
        }
        if ((name.equals("defog") || name.equals("rapidspin") || name.equals("mortalspin")) && hasHazards()) {
            return new MoveAdvice(1.0D, false, false, "clear your team's entry hazards");
        }
        return new MoveAdvice(0, false, false, "utility effect not estimated");
    }

    private static boolean hasAttackCategory(Fighter own, Stats stat) {
        for (Move move : own.own.getMoveSet()) {
            if (move != null && move.getCurrentPp() > 0 && damaging(move.getTemplate())
                    && (move.getDamageCategory() == DamageCategories.INSTANCE.getSPECIAL()) == (stat == Stats.SPECIAL_ATTACK)) return true;
        }
        return false;
    }

    private static boolean statusBlocked(Fighter foe, String status, JsonObject effect) {
        if (foe.ability.equals("comatose") || foe.ability.equals("purifyingsalt")) return true;
        if (effect.has("powder") && (hasType(foe, "grass") || foe.ability.equals("overcoat") || foe.item.endsWith("safetygoggles"))) return true;
        return switch (status) {
            case "brn" -> hasType(foe, "fire") || foe.ability.equals("waterveil") || foe.ability.equals("waterbubble");
            case "par" -> hasType(foe, "electric") || foe.ability.equals("limber");
            case "psn", "tox" -> hasType(foe, "poison") || hasType(foe, "steel") || foe.ability.equals("immunity");
            case "slp" -> foe.ability.equals("insomnia") || foe.ability.equals("vitalspirit") || foe.ability.equals("sweetveil");
            default -> false;
        };
    }

    private static double damage(Fighter attacker, Fighter defender, MoveTemplate move, ElementalType type, double roll) {
        if (!damaging(move)) return 0;
        double effectiveness = multiplier(attacker, defender, type);
        if (effectiveness == 0) return 0;
        JsonObject effect = effects(move);
        if (effect.has("damage")) {
            JsonElement fixed = effect.get("damage");
            double result = fixed.getAsString().equals("level") ? attacker.level() : fixed.getAsDouble();
            return protectedDamage(result, attacker, defender, 1);
        }
        if (effect.has("ohko")) {
            if (defender.ability.equals("sturdy") && !bypassesAbility(attacker)) return 0;
            return attacker.level() >= defender.level() ? protectedDamage(defender.hp, attacker, defender, 1) : 0;
        }
        String moveId = id(move.getName());
        if (List.of("superfang", "naturesmadness", "ruination").contains(moveId)) return Math.max(1, defender.hp / 2);
        if (moveId.equals("endeavor")) return Math.max(0, defender.hp - attacker.hp);
        if (List.of("counter", "mirrorcoat", "metalburst").contains(moveId)) return 0; // Reactive damage is not guaranteed.
        double power = move.getPower();
        if (moveId.equals("lowkick") || moveId.equals("grassknot")) {
            double weight = defender.own != null ? defender.own.getForm().getWeight()
                    : defender.live.getSpecies().getForm(defender.live.getProperties().getAspects()).getWeight();
            power = weight < 10 ? 20 : weight < 25 ? 40 : weight < 50 ? 60 : weight < 100 ? 80 : weight < 200 ? 100 : 120;
        } else if (power <= 0) {
            power = roll == 1 ? 120 : 40; // Conservative bounds for other variable-power attacks.
        }
        String name = id(move.getName());
        if ((name.equals("hex") || name.equals("venoshock") || name.equals("infernalparade") || name.equals("barbbarrage"))
                && !defender.status.isEmpty()) power *= 2;
        if (name.equals("facade") && !attacker.status.isEmpty()) power *= 2;
        if (name.equals("brine") && defender.hp <= defender.maxHp / 2) power *= 2;
        if (name.equals("eruption") || name.equals("waterspout") || name.equals("dragonenergy")) power *= attacker.hp / attacker.maxHp;
        boolean special = move.getDamageCategory() == DamageCategories.INSTANCE.getSPECIAL();
        double attack = attacker.stat(special ? Stats.SPECIAL_ATTACK : Stats.ATTACK);
        if (name.equals("facade") && attacker.status.equals("brn") && !attacker.ability.equals("guts")) attack *= 2;
        if (name.equals("bodypress")) attack = attacker.stat(Stats.DEFENCE);
        if (name.equals("foulplay")) attack = defender.stat(Stats.ATTACK);
        boolean physicalDefence = !special || name.equals("psyshock") || name.equals("psystrike") || name.equals("secretsword");
        double defence = defender.stat(physicalDefence ? Stats.DEFENCE : Stats.SPECIAL_DEFENCE);
        double stab = hasType(attacker, type.showdownId()) ? attacker.ability.equals("adaptability") ? 2 : 1.5D : 1;
        double hits = 1;
        if (effect.has("multihit")) {
            JsonElement multi = effect.get("multihit");
            hits = multi.isJsonArray() ? (attacker.ability.equals("skilllink") || roll == 1 ? multi.getAsJsonArray().get(1).getAsDouble()
                    : roll == 0.85D ? multi.getAsJsonArray().get(0).getAsDouble() : 3) : multi.getAsDouble();
        }
        double result = (((2.0D * attacker.level() / 5 + 2) * power * attack / defence / 50) + 2)
                * stab * effectiveness * roll * hits;
        if (attacker.item.endsWith("lifeorb")) result *= 1.3D;
        boolean bypass = bypassesAbility(attacker);
        if ((defender.ability.equals("multiscale") || defender.ability.equals("shadowshield"))
                && defender.hp >= defender.maxHp && (!bypass || defender.ability.equals("shadowshield"))) result *= 0.5D;
        return protectedDamage(result, attacker, defender, hits);
    }

    private static boolean bypassesAbility(Fighter attacker) {
        return List.of("moldbreaker", "teravolt", "turboblaze").contains(attacker.ability);
    }

    private static double protectedDamage(double result, Fighter attacker, Fighter defender, double hits) {
        if (hits == 1 && defender.hp >= defender.maxHp && (defender.item.endsWith("focussash")
                || defender.ability.equals("sturdy") && !bypassesAbility(attacker))) result = Math.min(result, defender.hp - 1);
        return Math.max(0, result);
    }

    private static double multiplier(Fighter attacker, Fighter defender, ElementalType type) {
        double base = CobblemonTeamAdvisor.moveMultiplier(type, defender.types);
        if (field.gravity && type.showdownId().equals("ground") && hasType(defender, "flying")) {
            List<ElementalType> groundedTypes = defender.types.stream().filter(t -> !t.showdownId().equals("flying")).toList();
            base = CobblemonTeamAdvisor.moveMultiplier(type, groundedTypes);
        }
        String ability = field.gravity && defender.ability.equals("levitate") ? "" : defender.ability;
        double result = BattleMatchupPolicy.effectiveMultiplier(base, type.showdownId(), ability, attacker.ability);
        if (ability.equals("wonderguard") && result <= 1 && !bypassesAbility(attacker)) return 0;
        return result;
    }

    private static double entryDamage(Fighter own) {
        double rock = 1;
        for (ElementalType type : own.types) {
            rock *= switch (type.showdownId()) {
                case "fire", "ice", "flying", "bug" -> 2;
                case "fighting", "ground", "steel" -> 0.5D;
                default -> 1;
            };
        }
        return own.maxHp * field.ally.entryFraction(rock, grounded(own), boots(own), own.ability.equals("magicguard"));
    }

    private static double residual(Fighter own) {
        if (own.ability.equals("magicguard")) return 0;
        double fraction = switch (own.status) {
            case "brn" -> 1.0D / 16;
            case "psn", "tox" -> own.ability.equals("poisonheal") ? -1.0D / 8 : 1.0D / 8;
            default -> 0;
        };
        if (own.item.endsWith("leftovers") || own.item.endsWith("blacksludge") && hasType(own, "poison")) fraction -= 1.0D / 16;
        return Math.max(0, own.maxHp * fraction);
    }

    private static boolean grounded(Fighter own) {
        return field.gravity || !hasType(own, "flying") && !own.ability.equals("levitate") && !own.item.endsWith("airballoon");
    }

    private static boolean boots(Fighter own) { return own.item.endsWith("heavydutyboots"); }
    private static boolean hasType(Fighter own, String type) { return own.types.stream().anyMatch(t -> t.showdownId().equals(type)); }
    private static boolean hasHazards() { return field.ally.rocks || field.ally.spikes > 0 || field.ally.toxicSpikes > 0 || field.ally.web; }
    private static String id(String value) { return CobblemonTeamAdvisor.normalizedId(value); }

    private static boolean damaging(MoveTemplate move) {
        return move.getDamageCategory() != DamageCategories.INSTANCE.getSTATUS();
    }

    private static JsonObject effects(MoveTemplate move) {
        JsonElement effect = EFFECTS.get(id(move.getName()));
        return effect == null ? NO_EFFECT : effect.getAsJsonObject();
    }

    private static double fraction(JsonObject effect, String key) {
        if (!effect.has(key)) return 0;
        var pair = effect.getAsJsonArray(key);
        return pair.get(0).getAsDouble() / pair.get(1).getAsDouble();
    }

    private static Stats statId(String stat) {
        return switch (stat) {
            case "atk" -> Stats.ATTACK;
            case "spa" -> Stats.SPECIAL_ATTACK;
            case "def" -> Stats.DEFENCE;
            case "spd" -> Stats.SPECIAL_DEFENCE;
            case "spe" -> Stats.SPEED;
            default -> null;
        };
    }

    private static JsonObject loadEffects() {
        try (var input = BattleAdvisor.class.getResourceAsStream("/battle-move-effects.json")) {
            if (input == null) throw new IllegalStateException("Missing bundled battle move effects");
            return new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot load bundled battle move effects", error);
        }
    }

    static void renderHint(GuiGraphics graphics, BattleMoveSelection selection, BattleSwitchPokemonSelection.SwitchTile best) {
        Pokemon active = CobblemonTeamAdvisor.activePokemon(selection);
        if (active == null) return;
        var opponents = selection.getRequest().getActivePokemon().getAdjacentOpponents();
        ClientBattlePokemon opponent = null;
        for (var target : opponents) {
            if (target instanceof com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon foe && foe.hasPokemon()) {
                opponent = foe.getBattlePokemon(); break;
            }
        }
        if (opponent == null) return;
        String hint;
        if (best != null && shouldSwitch(selection, best.getPokemon(), best.getShowdownPokemon(), opponent)) {
            hint = "Switch: " + best.getPokemon().getDisplayName(false).getString() + " — "
                    + matchup(best.getPokemon(), best.getShowdownPokemon(), opponent, false).reason();
        } else {
            BattleMoveSelection.MoveTile recommended = null;
            double score = Double.NEGATIVE_INFINITY;
            for (var tile : selection.getMoveTiles()) {
                if (!tile.getSelectable()) continue;
                MoveAdvice advice = moveAdvice(tile);
                if (advice.score() > score) {
                    recommended = tile; score = advice.score();
                }
            }
            hint = recommended == null ? "No legal move available" : "Stay: " + recommended.getMoveTemplate().getDisplayName().getString()
                    + " — " + moveAdvice(recommended).reason();
            if (selection.getMoveSet().getTrapped()) hint += "; trapped";
        }
        hint(graphics, hint, CobblemonTeamAdvisor.opponentMoves(opponent).source() + "; damage / speed are estimates");
    }

    static void renderSwitchHint(GuiGraphics graphics, BattleSwitchPokemonSelection.SwitchTile tile, ClientBattlePokemon opponent) {
        if (tile == null || opponent == null) return;
        hint(graphics, "Best switch: " + tile.getPokemon().getDisplayName(false).getString() + " — "
                + matchup(tile.getPokemon(), tile.getShowdownPokemon(), opponent, false).reason(),
                CobblemonTeamAdvisor.opponentMoves(opponent).source() + "; damage / speed are estimates");
    }

    private static void hint(GuiGraphics graphics, String first, String second) {
        Minecraft minecraft = Minecraft.getInstance();
        int width = Math.max(60, minecraft.getWindow().getGuiScaledWidth() - 80);
        graphics.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(first, width), 6, 6, 0xFFB8FFD0, true);
        graphics.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(second, width), 6, 17, 0xFFE8E8E8, true);
    }
}
