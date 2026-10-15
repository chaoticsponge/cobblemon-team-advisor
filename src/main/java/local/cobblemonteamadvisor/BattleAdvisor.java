package local.cobblemonteamadvisor;

import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.moves.categories.DamageCategories;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.battles.MoveTarget;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
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
import java.util.Set;
import java.util.HashSet;

public final class BattleAdvisor {
    private static final JsonObject EFFECTS = loadEffects();
    private static final JsonObject NO_EFFECT = new JsonObject();
    private static final Map<BattleMoveSelection.MoveTile, MoveAdvice> MOVE_CACHE = new HashMap<>();
    private static final Map<MatchupKey, Matchup> MATCHUP_CACHE = new HashMap<>();
    private record MatchupKey(UUID pokemon, UUID opponent, boolean active, String condition) { }
    private static final Map<UUID, TrainerRoster.Member> MEMBER_CACHE = new HashMap<>();
    private static final Map<UUID, List<TrainerRoster.Member>> ROSTER_CACHE = new HashMap<>();
    private static final Map<String, BattlePlanner.Plan> PLAN_CACHE = new HashMap<>();
    private static final Map<UUID, Set<String>> SEEN_TEAMS = new HashMap<>();
    private static final Map<UUID, String> FAINTED = new HashMap<>();
    private static final Map<UUID, Boolean> RAID_CACHE = new HashMap<>();
    private static final Map<UUID, Float> HEALTH = new HashMap<>();
    private static final Map<UUID, Set<String>> OBSERVED_MOVES = new HashMap<>();
    private static BattleObservations observations = new BattleObservations();
    private static BattleActionGuard guard = new BattleActionGuard();
    private static BattleSwitchPolicy switchPolicy = new BattleSwitchPolicy();
    private static SingleActionRequest submittedRequest;
    private static UUID submittedSwitch;
    private record PendingHit(UUID target, double before, double predicted, BattleObservations.DamageKey key, boolean clean) { }
    private record SeenMove(UUID user, int priority, String context) { }
    private static PendingHit pendingHit;
    private static SeenMove firstMove;
    private static boolean randomOrder;
    private static UUID battleId;
    private static BattleFieldState field = new BattleFieldState();
    private static BattleMoveState moveState = new BattleMoveState();

    private BattleAdvisor() { }

    static void syncBattle() {
        // Reuse identical decisions within a tick; packets also invalidate the caches.
        MOVE_CACHE.clear();
        RAID_CACHE.clear();
        MATCHUP_CACHE.clear();
        MEMBER_CACHE.clear();
        ROSTER_CACHE.clear();
        PLAN_CACHE.clear();
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        TrainerBattleTracker.sync(battle);
        UUID id = battle == null ? null : battle.getBattleId();
        if (battle != null && submittedRequest != null && !battle.getPendingActionRequests().contains(submittedRequest)) {
            submittedRequest = null; submittedSwitch = null;
        }
        if (!java.util.Objects.equals(id, battleId)) {
            battleId = id;
            field = new BattleFieldState();
            moveState = new BattleMoveState();
            observations = new BattleObservations();
            guard = new BattleActionGuard();
            switchPolicy = new BattleSwitchPolicy();
            SEEN_TEAMS.clear(); FAINTED.clear(); HEALTH.clear(); OBSERVED_MOVES.clear();
            submittedRequest = null; submittedSwitch = null;
            pendingHit = null; firstMove = null; randomOrder = false;
        }
    }

    public static void observeMessages(List<Component> messages) {
        syncBattle();
        if (battleId == null) return;
        for (Component message : messages) observe(message);
    }

    public static void observeEntry(UUID pokemon) {
        syncBattle();
        if (battleId != null) {
            moveState.enter(pokemon);
            switchPolicy.entered(pokemon);
            HEALTH.remove(pokemon);
            pendingHit = null;
        }
    }

    static boolean freshSelection(BattleGUI gui) {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null || !battle.getMustChoose() || gui.getCurrentActionSelection() == null) return false;
        SingleActionRequest request = gui.getCurrentActionSelection().getRequest();
        if (request != battle.getFirstUnansweredRequest() || request.getResponse() != null) return false;
        if (!request.getActivePokemon().hasPokemon()) return false;
        UUID active = request.getActivePokemon().getBattlePokemon().getUuid();
        return request.getSide().getPokemon().stream().anyMatch(pokemon -> pokemon.getActive() && pokemon.getUuid().equals(active));
    }

    static boolean canSwitch(SingleActionRequest request, Pokemon candidate, ShowdownPokemon snapshot) {
        if (candidate == null || snapshot == null || !request.getActivePokemon().hasPokemon()) return false;
        Set<UUID> active = new HashSet<>();
        for (var pokemon : request.getActivePokemon().getSidePokemon()) if (pokemon.hasPokemon()) active.add(pokemon.getBattlePokemon().getUuid());
        for (ShowdownPokemon pokemon : request.getSide().getPokemon()) if (pokemon.getActive()) active.add(pokemon.getUuid());
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        boolean reserved = battle != null && battle.getPendingActionRequests().stream().anyMatch(other -> other != request
                && other.getResponse() instanceof SwitchActionResponse response && response.getNewPokemonId().equals(candidate.getUuid()));
        boolean trapped = !request.getForceSwitch() && request.getMoveSet() != null && request.getMoveSet().getTrapped();
        return guard.canSwitch(request, candidate.getUuid(), request.getActivePokemon().getBattlePokemon().getUuid(), active,
                snapshot.getCondition().contains("fnt") || snapshot.getCondition().equals("0") || snapshot.getCondition().startsWith("0/"), trapped, reserved);
    }

    static void submittedSwitch(SingleActionRequest request, UUID pokemon) {
        submittedRequest = request;
        submittedSwitch = pokemon;
        UUID opponent = null;
        for (var target : request.getActivePokemon().getAdjacentOpponents()) {
            if (target instanceof com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon foe && foe.hasPokemon()) {
                opponent = foe.getBattlePokemon().getUuid(); break;
            }
        }
        switchPolicy.submitted(pokemon, opponent, request.getForceSwitch());
    }

    public static void observeInvalidChoice() {
        syncBattle();
        switchPolicy.rejected();
        if (submittedRequest != null && submittedSwitch != null) {
            guard.reject(submittedRequest, submittedSwitch);
            com.mojang.logging.LogUtils.getLogger().warn("Advisor suppressed a rejected switch for the current request");
        }
        submittedRequest = null; submittedSwitch = null;
    }

    private static List<ClientBattlePokemon> activePokemon() {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        List<ClientBattlePokemon> result = new ArrayList<>();
        if (battle != null) {
            for (var side : battle.getSides()) for (var actor : side.getActors()) for (var active : actor.getActivePokemon()) {
                if (active.hasPokemon()) result.add(active.getBattlePokemon());
            }
        }
        return result;
    }

    private static void observeMoveMessage(TranslatableContents translation) {
        Object[] args = translation.getArgs();
        if (translation.getKey().equals("cobblemon.battle.turn") && args.length > 0) {
            String number = args[0] instanceof Component component ? component.getString() : args[0].toString();
            try {
                int turn = Integer.parseInt(number);
                if (turn > moveState.turn()) { firstMove = null; pendingHit = null; randomOrder = false; }
                moveState.startTurn(turn, activePokemon().stream().map(ClientBattlePokemon::getUuid).toList());
            } catch (NumberFormatException ignored) { /* Ignore unrelated or malformed turn text. */ }
        }
        boolean used = translation.getKey().equals("cobblemon.battle.used_move") || translation.getKey().equals("cobblemon.battle.used_move_on");
        boolean prevented = translation.getKey().startsWith("cobblemon.battle.cant.");
        if ((used || prevented) && args.length > 0 && args[0] instanceof Component source) {
            List<ClientBattlePokemon> matches = activePokemon().stream()
                    .filter(pokemon -> pokemon.getDisplayName().getString().equals(source.getString())).toList();
            if (matches.size() == 1) {
                if (prevented) moveState.attempted(matches.getFirst().getUuid());
                if (used && args.length > 1 && args[1] instanceof Component move) {
                    String name = translatedMoveId(move);
                    if (name != null) {
                        ClientBattlePokemon user = matches.getFirst();
                        if (moveState.hasEffect(user.getUuid(), "charging")) moveState.effect(user.getUuid(), "charging", false, null);
                        moveState.used(user.getUuid(), name, choice(fighter(user)));
                        if (OBSERVED_MOVES.computeIfAbsent(user.getUuid(), ignored -> new HashSet<>()).add(name)) {
                            MEMBER_CACHE.clear(); ROSTER_CACHE.clear();
                        }
                        MoveTemplate template = Moves.getByName(name);
                        if (template != null) beginMove(matches.getFirst(), template, args.length > 2 ? args[2] : null);
                    }
                }
            }
        }
    }

    private static ClientBattlePokemon namedPokemon(Object argument) {
        if (!(argument instanceof Component component)) return null;
        List<ClientBattlePokemon> matches = activePokemon().stream()
                .filter(pokemon -> pokemon.getDisplayName().getString().equals(component.getString())).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static Fighter fighter(ClientBattlePokemon pokemon) {
        for (Pokemon own : party()) if (own.getUuid().equals(pokemon.getUuid())) return mine(own, null, true);
        return enemy(pokemon);
    }

    private static String fieldContext() {
        return field.weather + ":" + field.terrain + ":" + field.gravity + ":" + field.ally.reflect + ":" + field.ally.lightScreen
                + ":" + field.ally.auroraVeil + ":" + field.opponent.reflect + ":" + field.opponent.lightScreen + ":" + field.opponent.auroraVeil;
    }

    private static String fighterContext(Fighter pokemon) {
        return pokemon.level() + ":" + pokemon.status + ":" + pokemon.ability + ":" + pokemon.item + ":"
                + (pokemon.live == null || pokemon.entering ? "" : pokemon.live.getStatChanges().toString())
                + ":" + (pokemon.hp >= pokemon.maxHp);
    }

    private static String damageContext(Fighter attacker, Fighter defender) {
        return fieldContext() + "/" + fighterContext(attacker) + "/" + fighterContext(defender);
    }

    private static String speedContext(Fighter first, Fighter second) {
        String a = first.uuid() + ":" + first.stat(Stats.SPEED) + ":" + first.status + ":" + first.item;
        String b = second.uuid() + ":" + second.stat(Stats.SPEED) + ":" + second.status + ":" + second.item;
        return (first.uuid().compareTo(second.uuid()) < 0 ? a + "/" + b : b + "/" + a)
                + "/" + field.weather + "/" + field.terrain + "/" + field.trickRoom + "/" + field.ally.tailwind + "/" + field.opponent.tailwind;
    }

    private static boolean randomSpeed(Fighter pokemon) {
        return pokemon.item.endsWith("quickclaw") || pokemon.item.endsWith("custapberry") || pokemon.item.endsWith("laggingtail")
                || pokemon.item.endsWith("fullincense") || pokemon.ability.equals("quickdraw") || pokemon.ability.equals("stall");
    }

    private static void beginMove(ClientBattlePokemon user, MoveTemplate move, Object targetArgument) {
        switchPolicy.acted(user.getUuid());
        pendingHit = null;
        Fighter attacker = fighter(user);
        ClientBattlePokemon target = namedPokemon(targetArgument);
        if (target == null) {
            List<ClientBattlePokemon> foes = activePokemon().stream().filter(p -> p.getActor().getSide() != user.getActor().getSide()).toList();
            if (foes.size() == 1) target = foes.getFirst();
        }
        if (target == null) return;
        Fighter defender = fighter(target);
        String context = speedContext(attacker, defender);
        int priority = priority(attacker, move);
        if (firstMove != null && activePokemon().size() == 2 && !firstMove.user.equals(user.getUuid()) && firstMove.context.equals(context)) {
            observations.order(firstMove.user, user.getUuid(), context, firstMove.priority, priority,
                    !randomOrder && !randomSpeed(attacker) && !randomSpeed(defender));
        } else if (firstMove == null) {
            firstMove = new SeenMove(user.getUuid(), priority, context);
        }
        if (damaging(move) && user.getActor().getSide() != target.getActor().getSide()) {
            boolean clean = !unknownRaidHp(defender) && !effects(move).has("multihit") && !moveState.hasEffect(target.getUuid(), "substitute")
                    && !moveState.protectedNow(target.getUuid()) && !(defender.hp >= defender.maxHp
                    && (defender.item.endsWith("focussash") || defender.ability.equals("sturdy")));
            pendingHit = new PendingHit(target.getUuid(), hpFraction(target),
                    rawDamage(attacker, defender, move, move.getElementalType(), 0.925D) / defender.maxHp,
                    new BattleObservations.DamageKey(user.getUuid(), target.getUuid(), id(move.getName()), damageContext(attacker, defender)), clean);
        }
    }

    public static void observeHealth(BattleHealthChangePacket packet) {
        syncBattle();
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) return;
        var pair = battle.getPokemonFromPNX(packet.getPnx());
        if (pair == null || pair.getSecond() == null || !pair.getSecond().hasPokemon()) return;
        ClientBattlePokemon pokemon = pair.getSecond().getBattlePokemon();
        double maximum = packet.getNewMaxHealth() == null ? pokemon.getMaxHp() : packet.getNewMaxHealth();
        if (pendingHit != null && pendingHit.target.equals(pokemon.getUuid())) {
            double after = BattleMatchupPolicy.healthFraction(packet.getNewHealth(), maximum, pokemon.isHpFlat());
            observations.damage(pendingHit.key, pendingHit.predicted, pendingHit.before - after,
                    pendingHit.clean && packet.getNewMaxHealth() == null, after <= 0);
            pendingHit = null;
        }
        HEALTH.put(pokemon.getUuid(), packet.getNewHealth());
        if (packet.getNewHealth() <= 0) FAINTED.put(pokemon.getUuid(), pokemon.getActor().getUuid() + "|" + memberKey(pokemon));
    }

    private static void observeEffect(TranslatableContents translation) {
        String key = translation.getKey();
        if (key.startsWith("cobblemon.battle.damage.") || key.startsWith("cobblemon.battle.heal.")
                || key.startsWith("cobblemon.battle.crit") || key.equals("cobblemon.battle.activate.substitute")) pendingHit = null;
        if (key.contains("quickclaw") || key.contains("quickdraw") || key.contains("custap")) randomOrder = true;
        Object[] args = translation.getArgs();
        if (args.length == 0) return;
        ClientBattlePokemon pokemon = namedPokemon(args[0]);
        if (pokemon == null) return;
        String[] parts = key.split("\\.");
        if (parts.length != 4) return;
        String phase = parts[2], effect = parts[3];
        if (phase.equals("prepare")) moveState.effect(pokemon.getUuid(), "charging", true, effect);
        if (List.of("taunt", "encore", "disable", "substitute", "protect").contains(effect)
                && (phase.equals("start") || phase.equals("end") || phase.equals("singleturn") || phase.equals("activate"))) {
            String move = args.length > 1 && args[1] instanceof Component component ? translatedMoveId(component) : null;
            moveState.effect(pokemon.getUuid(), effect, !phase.equals("end"), move);
        }
    }

    private static boolean choice(Fighter own) {
        return own.item.endsWith("choiceband") || own.item.endsWith("choicespecs") || own.item.endsWith("choicescarf");
    }

    private static int priority(Fighter attacker, MoveTemplate move) {
        int priority = move.getPriority();
        if (!damaging(move) && attacker.ability.equals("prankster")) priority++;
        if (move.getElementalType().showdownId().equals("flying") && attacker.hp >= attacker.maxHp && attacker.ability.equals("galewings")) priority++;
        if (attacker.ability.equals("triage") && (effects(move).has("heal") || effects(move).has("drain"))) priority += 3;
        if (id(move.getName()).equals("grassyglide") && field.terrain.equals("grassyterrain") && grounded(attacker)) priority++;
        return priority;
    }

    private static boolean actsFirst(Fighter own, Fighter foe, MoveTemplate move, MoveTemplate reply) {
        int a = priority(own, move), b = priority(foe, reply);
        if (a == b) {
            Boolean learned = observations.first(own.uuid(), foe.uuid(), speedContext(own, foe));
            if (learned != null) return learned;
        }
        return BattleMatchupPolicy.actsFirst(a, own.stat(Stats.SPEED), b, foe.stat(Stats.SPEED), field.trickRoom);
    }

    private static boolean charging(Fighter user, MoveTemplate move) {
        if (!effects(move).has("charging") || user.item.endsWith("powerherb")) return false;
        String name = id(move.getName());
        if ((name.equals("solarbeam") || name.equals("solarblade")) && field.weather.equals("sunnyday")) return false;
        return !name.equals("electroshot") || !field.weather.equals("raindance");
    }

    private static double healing(Fighter own, MoveTemplate move) {
        double healing = fraction(effects(move), "heal") * own.maxHp;
        String name = id(move.getName());
        if (List.of("moonlight", "morningsun", "synthesis").contains(name)) {
            healing = own.maxHp * (field.weather.equals("sunnyday") ? 2.0 / 3 : field.weather.isEmpty() ? 0.5 : 0.25);
        }
        if (name.equals("shoreup") && field.weather.equals("sandstorm")) healing = own.maxHp * 2.0 / 3;
        return healing;
    }

    private static String translatedMoveId(Component component) {
        if (component.getContents() instanceof TranslatableContents translation && translation.getKey().startsWith("cobblemon.move.")) {
            String name = translation.getKey().substring("cobblemon.move.".length());
            if (!name.contains(".")) return id(name);
        }
        for (Component sibling : component.getSiblings()) {
            String name = translatedMoveId(sibling);
            if (name != null) return name;
        }
        return null;
    }

    private static void observe(Component message) {
        if (message.getContents() instanceof TranslatableContents translation) {
            TrainerBattleTracker.message(translation.getKey(), translation.getArgs());
            field.observe(translation.getKey());
            observeMoveMessage(translation);
            observeEffect(translation);
            for (Object argument : translation.getArgs()) {
                if (argument instanceof Component component) observe(component);
            }
        }
        for (Component sibling : message.getSiblings()) observe(sibling);
    }

    record Matchup(int score, boolean survives, double incoming, double entry, String reason) { }
    record MoveAdvice(double score, boolean ko, boolean firstKo, String reason, double accuracy, boolean safeKo) {
        MoveAdvice(double score, boolean ko, boolean firstKo, String reason) {
            this(score, ko, firstKo, reason, 1, false);
        }
        String koLabel() { return ko ? accuracy >= 1 ? "KO" : "KO*" : ""; }
    }
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
                int base = live.getSpecies().getForm(live.getState().getCurrentAspects()).getBaseStats().getOrDefault(stat, 1);
                TrainerRoster.Member member = trainerMember(live);
                int iv = live.getProperties().getIvs() != null ? live.getProperties().getIvs().getOrDefault(stat)
                        : member != null && member.ivs() != null ? member.ivs().getOrDefault(stat.getShowdownId(), 31) : 31;
                int ev = live.getProperties().getEvs() != null ? live.getProperties().getEvs().getOrDefault(stat)
                        : member != null && member.evs() != null ? member.evs().getOrDefault(stat.getShowdownId(), 0) : 0;
                value = Math.floor((2 * base + iv + ev / 4) * live.getLevel() / 100.0D) + 5;
                String knownNature = live.getProperties().getNature() != null ? live.getProperties().getNature() : member == null ? null : member.nature();
                if (knownNature != null) {
                    var nature = Natures.getNature(knownNature);
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
                if ((ability.equals("swiftswim") && field.weather.equals("raindance"))
                        || (ability.equals("chlorophyll") && field.weather.equals("sunnyday"))
                        || (ability.equals("sandrush") && field.weather.equals("sandstorm"))
                        || (ability.equals("slushrush") && (field.weather.equals("snow") || field.weather.equals("hail")))
                        || (ability.equals("surgesurfer") && field.terrain.equals("electricterrain"))) value *= 2;
                if (entering && grounded(this) && field.ally.web && !boots(this)) value *= 2.0D / 3;
            }
            if (stat == Stats.SPECIAL_DEFENCE && hasType(this, "rock") && field.weather.equals("sandstorm")) value *= 1.5;
            if (stat == Stats.DEFENCE && hasType(this, "ice") && field.weather.equals("snow")) value *= 1.5;
            if (stat == Stats.ATTACK && item.endsWith("choiceband")) value *= 1.5D;
            if (stat == Stats.SPECIAL_ATTACK && item.endsWith("choicespecs")) value *= 1.5D;
            return Math.max(1, value);
        }

        int level() { return own != null ? own.getLevel() : live.getLevel(); }
        UUID uuid() { return own != null ? own.getUuid() : live.getUuid(); }
    }

    private static Fighter mine(Pokemon pokemon, ShowdownPokemon showdown, boolean active) {
        ClientBattlePokemon live = active ? livePokemon(pokemon.getUuid()) : null;
        double max = Math.max(1, pokemon.getMaxHealth());
        double hp = pokemon.getCurrentHealth();
        String status = pokemon.getStatus() == null || pokemon.getStatus().getStatus() == null ? ""
                : pokemon.getStatus().getStatus().getShowdownName();
        if (live != null) {
            float health = HEALTH.getOrDefault(live.getUuid(), live.getHpValue());
            hp = live.isHpFlat() ? health : hpFraction(live) * max;
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

    private static String memberKey(ClientBattlePokemon pokemon) {
        return id(pokemon.getSpecies().getName()) + ":" + pokemon.getLevel();
    }

    static List<String> knownMoves(ClientBattlePokemon pokemon) {
        Set<String> names = new java.util.LinkedHashSet<>();
        if (pokemon.getProperties().getMoves() != null) pokemon.getProperties().getMoves().forEach(name -> names.add(id(name)));
        names.addAll(OBSERVED_MOVES.getOrDefault(pokemon.getUuid(), Set.of()));
        return List.copyOf(names);
    }

    private static List<TrainerRoster.Member> roster(ClientBattlePokemon pokemon) {
        if (pokemon.getActor() == null) return List.of();
        UUID actor = pokemon.getActor().getUuid();
        Set<String> seen = SEEN_TEAMS.computeIfAbsent(actor, ignored -> new HashSet<>());
        seen.add(memberKey(pokemon));
        return ROSTER_CACHE.computeIfAbsent(actor, ignored -> TrainerRoster.match(pokemon.getActor().getDisplayName().getString(),
                seen, memberKey(pokemon), knownMoves(pokemon)));
    }

    static TrainerRoster.Member trainerMember(ClientBattlePokemon pokemon) {
        return MEMBER_CACHE.computeIfAbsent(pokemon.getUuid(), ignored -> {
            List<TrainerRoster.Member> matches = roster(pokemon).stream().filter(member -> member.key().equals(memberKey(pokemon))
                    && member.moves().containsAll(knownMoves(pokemon))).toList();
            return matches.size() == 1 ? matches.getFirst() : null;
        });
    }

    private static List<Pokemon> party() {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        var player = Minecraft.getInstance().player;
        if (battle == null || player == null) return List.of();
        for (var side : battle.getSides()) for (var actor : side.getActors()) {
            if (actor.getUuid().equals(player.getUUID())) return actor.getPokemon();
        }
        return List.of();
    }

    private static double reservePenalty(Fighter own, Fighter foe, double remainingHp) {
        List<TrainerRoster.Member> remaining = new ArrayList<>(roster(foe.live));
        if (remaining.isEmpty()) return 0;
        String prefix = foe.live.getActor().getUuid() + "|";
        for (String defeated : FAINTED.values()) {
            if (defeated.startsWith(prefix)) removeMember(remaining, defeated.substring(prefix.length()));
        }
        if (foe.hp > 0) removeMember(remaining, memberKey(foe.live));
        double penalty = 0;
        for (TrainerRoster.Member member : remaining) {
            double rating = futureRating(member, own);
            double alternative = -Double.MAX_VALUE;
            for (Pokemon other : party()) {
                if (!other.getUuid().equals(own.own.getUuid())
                        && HEALTH.getOrDefault(other.getUuid(), (float) other.getCurrentHealth()) > other.getMaxHealth() * 0.25) {
                    alternative = Math.max(alternative, futureRating(member, mine(other, null, false)));
                }
            }
            penalty += BattleMatchupPolicy.counterCost(rating, alternative, own.hp, remainingHp);
        }
        return Math.min(24, penalty);
    }

    private static void removeMember(List<TrainerRoster.Member> members, String key) {
        for (int i = 0; i < members.size(); i++) if (members.get(i).key().equals(key)) { members.remove(i); return; }
    }

    private static double futureRating(TrainerRoster.Member member, Fighter defender) {
        double threat = futureThreat(member, defender);
        double offence = 0;
        var species = PokemonSpecies.getByName(member.species());
        if (species != null) {
            var form = species.getForm(member.aspects() == null ? Set.of() : Set.copyOf(member.aspects()));
            List<ElementalType> types = new ArrayList<>();
            for (ElementalType type : form.getTypes()) types.add(type);
            for (Move move : defender.own.getMoveSet()) if (move != null && move.getCurrentPp() > 0 && damaging(move.getTemplate())) {
                ElementalType type = move.getTemplate().getEffectiveElementalType(defender.own);
                offence = Math.max(offence, BattleMatchupPolicy.effectiveMultiplier(CobblemonTeamAdvisor.moveMultiplier(type, types),
                        type.showdownId(), id(member.ability()), defender.ability));
            }
        }
        return -BattleMatchupPolicy.threatScore(threat) + BattleMatchupPolicy.offensiveScore(offence);
    }

    private static double futureThreat(TrainerRoster.Member member, Fighter defender) {
        double worst = 0;
        boolean damaging = false;
        for (String name : member.moves()) {
            MoveTemplate move = Moves.getByName(name);
            if (move == null || !damaging(move)) continue;
            damaging = true;
            double multiplier = BattleMatchupPolicy.effectiveMultiplier(CobblemonTeamAdvisor.moveMultiplier(move.getElementalType(), defender.types),
                    move.getElementalType().showdownId(), defender.ability, id(member.ability()));
            if (effects(move).has("damage") && multiplier > 0) multiplier = 1;
            worst = Math.max(worst, multiplier);
        }
        return damaging ? worst : 1;
    }

    private static boolean raid(ClientBattlePokemon pokemon) {
        return RAID_CACHE.computeIfAbsent(pokemon.getUuid(), ignored -> {
            if (pokemon.getState().getCurrentAspects().contains("raid")) return true;
            var level = Minecraft.getInstance().level;
            if (level == null) return false;
            var dimension = level.dimension().location();
            if (dimension.getNamespace().equals("cobblemonraiddens") && dimension.getPath().contains("raid")) return true;
            for (var entity : level.entitiesForRendering()) {
                if (entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity live
                        && live.getPokemon().getUuid().equals(pokemon.getUuid())) {
                    return live.getPokemon().getAspects().contains("raid");
                }
            }
            return false;
        });
    }

    private static boolean unknownRaidHp(Fighter fighter) {
        // Raid Dens syncs a shared health fraction, not the server's scaled maximum HP.
        return !fighter.allied && fighter.live != null && raid(fighter.live);
    }

    private static Fighter enemy(ClientBattlePokemon pokemon) {
        var form = pokemon.getSpecies().getForm(pokemon.getState().getCurrentAspects());
        List<ElementalType> types = new ArrayList<>();
        for (ElementalType type : form.getTypes()) types.add(type);
        TrainerRoster.Member member = trainerMember(pokemon);
        int iv = pokemon.getProperties().getIvs() != null ? pokemon.getProperties().getIvs().getOrDefault(Stats.HP)
                : member != null && member.ivs() != null ? member.ivs().getOrDefault("hp", 31) : 31;
        int ev = pokemon.getProperties().getEvs() != null ? pokemon.getProperties().getEvs().getOrDefault(Stats.HP)
                : member != null && member.evs() != null ? member.evs().getOrDefault("hp", 0) : 0;
        double max = Math.floor((2 * form.getBaseStats().getOrDefault(Stats.HP, 1) + iv + ev / 4)
                * pokemon.getLevel() / 100.0D) + pokemon.getLevel() + 10;
        if (pokemon.getSpecies().getName().equalsIgnoreCase("shedinja")) max = 1;
        if (pokemon.getMaxHp() > 1 || pokemon.isHpFlat() && pokemon.getMaxHp() > 0) max = pokemon.getMaxHp();
        return new Fighter(null, pokemon, types, id(pokemon.getProperties().getAbility() != null ? pokemon.getProperties().getAbility()
                : member == null ? null : member.ability()), id(pokemon.getProperties().getHeldItem() != null ? pokemon.getProperties().getHeldItem()
                : member == null ? null : member.heldItem()), pokemon.getStatus() == null ? "" : pokemon.getStatus().getShowdownName(),
                hpFraction(pokemon) * max, max, false, false);
    }

    private static double hpFraction(ClientBattlePokemon pokemon) {
        return BattleMatchupPolicy.healthFraction(HEALTH.getOrDefault(pokemon.getUuid(), pokemon.getHpValue()),
                pokemon.getMaxHp(), pokemon.isHpFlat());
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
        var knowledge = CobblemonTeamAdvisor.opponentMoves(pokemon);
        for (String name : knowledge.names()) {
            if (moveState.unavailable(pokemon.getUuid(), id(name), knowledge.names(), knowledge.complete()) != null) continue;
            MoveTemplate move = Moves.getByName(name);
            Fighter foe = enemy(pokemon);
            if (move != null && moveState.restricted(pokemon.getUuid(), id(name), !damaging(move), choice(foe)) == null) moves.add(move);
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
            worstType = Math.max(worstType, multiplier(foe, own, move, move.getElementalType()));
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
        double bestDamage = 0;
        for (Move move : pokemon.getMoveSet()) {
            if (move != null && move.getCurrentPp() > 0 && damaging(move.getTemplate())
                    && (!active || moveState.unavailable(pokemon.getUuid(), id(move.getName()), ownMoveNames(pokemon), true) == null)) {
                bestDamage = Math.max(bestDamage, damage(own, foe, move.getTemplate(),
                        move.getTemplate().getEffectiveElementalType(pokemon), 0.85D));
            }
        }
        int score = BattleMatchupPolicy.matchupScore(worstType, own.hp, own.maxHp, entry, incoming, residual,
                bestDamage / Math.max(1, foe.hp));
        double afterHit = Math.max(0, own.hp - entry - incoming - Math.max(0, residual));
        boolean survives = afterHit > 0;
        String reason = own.hp <= entry ? "entry hazards would KO" : !survives ? "risks KO on next hit"
                : bestDamage == 0 ? "cannot damage the target" : !hasDamagingMove && !moves.isEmpty() ? "no damaging moves in known set"
                : worstType == 0 ? "immune to damaging moves" : worstType < 1 ? "resists enemy coverage" : "survives next hit";
        if (!threat.isEmpty() && worstType >= 1) reason += " (" + threat + ")";
        if (entry > 0) reason += "; entry cost " + Math.round(100 * entry / own.maxHp) + "%";
        if (survives) reason += "; HP " + Math.round(100 * own.hp / own.maxHp) + "% → "
                + Math.round(100 * afterHit / own.maxHp) + "% after hit";
        return new Matchup(score, survives, incoming, entry, reason);
    }

    private static boolean available(Fighter user, MoveTemplate move) {
        if (id(move.getName()).equals("rest") && grounded(user)
                && (field.terrain.equals("electricterrain") || field.terrain.equals("mistyterrain"))) return false;
        return user.entering || moveState.unavailable(user.uuid(), id(move.getName()),
                user.own != null ? ownMoveNames(user.own) : CobblemonTeamAdvisor.opponentMoves(user.live).names(), user.own != null
                        || CobblemonTeamAdvisor.opponentMoves(user.live).complete()) == null
                && moveState.restricted(user.uuid(), id(move.getName()), !damaging(move), choice(user)) == null;
    }

    private static double moveAccuracy(Fighter user, Fighter target, MoveTemplate move) {
        String name = id(move.getName());
        if (user.ability.equals("noguard") || target.ability.equals("noguard")) return 1;
        if ((name.equals("thunder") || name.equals("hurricane")) && field.weather.equals("raindance")) return 1;
        if (name.equals("blizzard") && (field.weather.equals("hail") || field.weather.equals("snow"))) return 1;
        double accuracy = (name.equals("thunder") || name.equals("hurricane")) && field.weather.equals("sunnyday") ? 50 : move.getAccuracy();
        double modifier = 1;
        if (user.ability.equals("hustle") && move.getDamageCategory() == DamageCategories.INSTANCE.getPHYSICAL()) modifier *= 0.8;
        if (user.ability.equals("compoundeyes")) modifier *= 1.3;
        if (user.item.endsWith("widelens")) modifier *= 1.1;
        return BattleMatchupPolicy.accuracy(accuracy, user.stage(Stats.ACCURACY), target.stage(Stats.EVASION), modifier);
    }

    private static BattlePlanner.Action action(Fighter user, Fighter target, MoveTemplate move, ElementalType type, int pp) {
        String name = id(move.getName());
        JsonObject effect = effects(move);
        double heal = healing(user, move);
        if (name.equals("rest") && !user.status.equals("slp")) heal = user.maxHp;
        // Wish is delayed beyond the action itself; omit immediate healing rather than promise a false rescue.
        double attack = 1, defence = 1, speed = 1;
        if (effect.has("boosts") && move.getTarget() == MoveTarget.self) {
            for (var boost : effect.getAsJsonObject("boosts").entrySet()) {
                Stats stat = statId(boost.getKey());
                if (stat == null || boost.getValue().getAsInt() <= 0) continue;
                double ratio = BattleMatchupPolicy.stageMultiplier(user.stage(stat) + boost.getValue().getAsInt())
                        / BattleMatchupPolicy.stageMultiplier(user.stage(stat));
                if (stat == Stats.ATTACK && hasAttackCategory(user, stat) || stat == Stats.SPECIAL_ATTACK && hasAttackCategory(user, stat)) attack = Math.max(attack, ratio);
                if (stat == Stats.DEFENCE || stat == Stats.SPECIAL_DEFENCE) defence = Math.max(defence, ratio);
                if (stat == Stats.SPEED) speed = ratio;
            }
        }
        String status = effect.has("status") && target.status.isEmpty() ? effect.get("status").getAsString() : "";
        if (!status.isEmpty() && (statusBlocked(target, status, effect) || name.equals("thunderwave") && hasType(target, "ground"))) status = "";
        double extraResidual = switch (status) {
            case "brn" -> target.maxHp / 16;
            case "psn", "tox" -> target.maxHp / 8;
            default -> 0;
        };
        double recoil = user.ability.equals("rockhead") || user.ability.equals("magicguard") ? 0 : fraction(effect, "recoil");
        if (effect.has("selfdestruct")) recoil = user.hp / Math.max(1, target.hp);
        BattlePlanner.Effect modeled = new BattlePlanner.Effect(heal, fraction(effect, "drain"), recoil, attack, defence, speed,
                unknownRaidHp(target) ? 0 : extraResidual, status, Set.of("protect", "detect", "kingsshield", "spikyshield", "banefulbunker", "silktrap", "burningbulwark").contains(name),
                name.equals("substitute"), name.equals("rest") ? "slp" : "");
        return new BattlePlanner.Action(name, unknownRaidHp(target) ? 0 : damage(user, target, move, type, 0.925D), moveAccuracy(user, target, move),
                priority(user, move), pp, modeled, Set.of("firstimpression", "fakeout", "matblock").contains(name),
                Set.of("gigatonhammer", "bloodmoon").contains(name), move.getDamageCategory() == DamageCategories.INSTANCE.getPHYSICAL(),
                user.ability.equals("infiltrator") || effect.has("sound"), effect.has("recharge"), charging(user, move), effect.has("breaksProtect"));
    }

    private static List<BattlePlanner.Action> actions(Fighter user, Fighter target, BattleMoveSelection selection) {
        List<BattlePlanner.Action> actions = new ArrayList<>();
        if (selection != null) {
            for (var tile : selection.getMoveTiles()) if (tile.getSelectable() && available(user, tile.getMoveTemplate())) {
                actions.add(action(user, target, tile.getMoveTemplate(), tile.getElementalType(), tile.getMove().getPp()));
            }
        } else if (user.own != null) {
            for (Move move : user.own.getMoveSet()) if (move != null && move.getCurrentPp() > 0 && available(user, move.getTemplate())) {
                actions.add(action(user, target, move.getTemplate(), move.getTemplate().getEffectiveElementalType(user.own), move.getCurrentPp()));
            }
        } else {
            for (MoveTemplate move : enemyMoves(user.live)) actions.add(action(user, target, move, move.getElementalType(), move.getPp()));
            actions.sort(java.util.Comparator.comparingDouble((BattlePlanner.Action move) -> move.damage() + move.effect().heal()
                    + (move.effect().protect() ? user.maxHp * 0.4 : 0) + (!move.effect().status().isEmpty() ? user.maxHp * 0.3 : 0)).reversed());
            if (actions.size() > 8) actions = new ArrayList<>(actions.subList(0, 8));
        }
        return actions;
    }

    private static BattlePlanner.Plan plan(Fighter own, Fighter foe, List<BattlePlanner.Action> actions, BattlePlanner.Action first, boolean switching) {
        double mySpeed = own.stat(Stats.SPEED), theirSpeed = foe.stat(Stats.SPEED);
        Boolean learned = observations.first(own.uuid(), foe.uuid(), speedContext(own, foe));
        if (learned != null) mySpeed = learned != field.trickRoom ? theirSpeed * 1.01 : theirSpeed * 0.99;
        boolean enemySubstitute = moveState.hasEffect(foe.uuid(), "substitute");
        BattlePlanner.Combatant mine = new BattlePlanner.Combatant(own.hp - (switching ? entryDamage(own) : 0), own.maxHp, mySpeed,
                residual(own), own.entering ? 0 : moveState.hasEffect(own.uuid(), "substitute") ? own.maxHp / 4 : 0, own.status, actions, own.entering ? "" : moveState.lastMove(own.uuid()),
                own.entering ? "" : moveState.effectMove(own.uuid(), "charging"));
        BattlePlanner.Combatant enemy = new BattlePlanner.Combatant(foe.hp, foe.maxHp, theirSpeed, unknownRaidHp(foe) ? 0 : residual(foe),
                enemySubstitute ? foe.maxHp / 4 : 0, foe.status, actions(foe, own, null), moveState.lastMove(foe.uuid()),
                moveState.effectMove(foe.uuid(), "charging"));
        BattlePlanner.Plan result = BattlePlanner.evaluate(mine, enemy, first, switching, field.trickRoom);
        return new BattlePlanner.Plan(result.value() - reservePenalty(own, foe, result.hpRemaining()),
                result.next(), result.survives(), result.hpRemaining());
    }

    private static BattlePlanner.Plan movePlan(BattleMoveSelection.MoveTile tile, Fighter own, Fighter foe) {
        String key = "move:" + own.uuid() + ":" + foe.uuid() + ":" + id(tile.getMoveTemplate().getName());
        return PLAN_CACHE.computeIfAbsent(key, ignored -> {
            List<BattlePlanner.Action> actions = actions(own, foe, tile.getMoveSelection());
            BattlePlanner.Action selected = action(own, foe, tile.getMoveTemplate(), tile.getElementalType(), tile.getMove().getPp());
            return plan(own, foe, actions, selected, false);
        });
    }

    private static BattlePlanner.Plan switchPlan(Pokemon pokemon, ShowdownPokemon showdown, ClientBattlePokemon opponent) {
        String key = "switch:" + pokemon.getUuid() + ":" + opponent.getUuid();
        return PLAN_CACHE.computeIfAbsent(key, ignored -> {
            Fighter own = mine(pokemon, showdown, false), foe = enemy(opponent);
            return plan(own, foe, actions(own, foe, null), BattlePlanner.Action.attack("switch", 0, 0), true);
        });
    }

    static boolean shouldSwitch(BattleMoveSelection selection, Pokemon candidate, ShowdownPokemon showdown, ClientBattlePokemon opponent) {
        Pokemon active = CobblemonTeamAdvisor.activePokemon(selection);
        if (active == null || opponent == null || candidate == null || selection.getMoveSet().getTrapped()
                || !canSwitch(selection.getRequest(), candidate, showdown)) return false;
        for (var tile : selection.getMoveTiles()) {
            if (tile.getSelectable() && (moveAdvice(tile).firstKo() || moveAdvice(tile).safeKo())) return false;
        }
        Matchup current = matchup(active, null, opponent, true);
        Matchup next = matchup(candidate, showdown, opponent, false);
        if (!next.survives()) return false;
        Fighter own = mine(active, null, true);
        Fighter foe = enemy(opponent);
        boolean canDamage = false;
        for (var tile : selection.getMoveTiles()) {
            if (tile.getSelectable() && Double.isFinite(moveAdvice(tile).score()) && damaging(tile.getMoveTemplate())
                    && damage(own, foe, tile.getMoveTemplate(), tile.getElementalType(), 0.85) > 0) {
                canDamage = true; break;
            }
        }
        Fighter replacement = mine(candidate, showdown, false);
        boolean replacementCanDamage = false;
        if (!canDamage) {
            for (Move move : candidate.getMoveSet()) {
                if (move != null && move.getCurrentPp() > 0 && damaging(move.getTemplate())
                        && damage(replacement, foe, move.getTemplate(), move.getTemplate().getEffectiveElementalType(candidate), 0.85) > 0) {
                    replacementCanDamage = true; break;
                }
            }
        }
        if (!switchPolicy.allows(active.getUuid(), opponent.getUuid(),
                BattleSwitchPolicy.emergency(!current.survives(), canDamage, replacementCanDamage))) return false;
        int lostBoosts = 0;
        for (Stats stat : List.of(Stats.ATTACK, Stats.SPECIAL_ATTACK, Stats.DEFENCE, Stats.SPECIAL_DEFENCE, Stats.SPEED)) {
            lostBoosts += Math.max(0, own.stage(stat));
        }
        double stayValue = -Double.MAX_VALUE;
        for (var tile : selection.getMoveTiles()) if (tile.getSelectable() && Double.isFinite(moveAdvice(tile).score())) {
            stayValue = Math.max(stayValue, movePlan(tile, own, enemy(opponent)).value());
        }
        BattlePlanner.Plan switchPlan = switchPlan(candidate, showdown, opponent);
        int cost = current.survives() ? 4 + Math.min(8, lostBoosts * 2) : 0;
        double gain = switchPlan.value() - stayValue;
        return BattleSwitchPolicy.worthwhile(gain, cost, current.score(), next.score(),
                current.incoming() / Math.max(1, own.hp), next.incoming() / Math.max(1, replacement.hp - next.entry()));
    }

    static MoveAdvice moveAdvice(BattleMoveSelection.MoveTile tile) {
        return MOVE_CACHE.computeIfAbsent(tile, BattleAdvisor::calculateMoveAdvice);
    }

    private static List<String> ownMoveNames(Pokemon pokemon) {
        List<String> names = new ArrayList<>();
        for (Move move : pokemon.getMoveSet()) if (move != null) names.add(id(move.getName()));
        return names;
    }

    private static MoveAdvice calculateMoveAdvice(BattleMoveSelection.MoveTile tile) {
        Pokemon pokemon = tile.getPokemon();
        MoveTemplate move = tile.getMoveTemplate();
        if (pokemon == null || move == null) return new MoveAdvice(0, false, false, "legal fallback");
        String restriction = moveState.unavailable(pokemon.getUuid(), id(move.getName()), ownMoveNames(pokemon), true);
        if (restriction == null) restriction = moveState.restricted(pokemon.getUuid(), id(move.getName()), !damaging(move),
                choice(mine(pokemon, null, true)));
        if (restriction != null) return new MoveAdvice(Double.NEGATIVE_INFINITY, false, false, restriction);
        var target = CobblemonTeamAdvisor.target(tile);
        if (target == null || !target.hasPokemon()) return new MoveAdvice(0, false, false, "legal fallback");
        Fighter own = mine(pokemon, null, true);
        Fighter foe = enemy(target.getBattlePokemon());
        double accuracy = moveAccuracy(own, foe, move);
        List<MoveTemplate> replies = enemyMoves(foe.live);
        double incoming = 0;
        double before = 0;
        boolean first = true;
        for (MoveTemplate reply : replies) {
            boolean precedes = actsFirst(own, foe, move, reply);
            if (!precedes) first = false;
            double hit = damage(foe, own, reply, reply.getElementalType(), 1.0D);
            incoming = Math.max(incoming, hit);
            if (!precedes) before = Math.max(before, hit);
        }
        if (replies.isEmpty()) { incoming = own.maxHp * 0.25D; before = incoming; first = false; }
        BattlePlanner.Plan forecast = movePlan(tile, own, foe);
        if (!damaging(move)) {
            MoveAdvice utility = utility(own, foe, move, accuracy, incoming, before);
            return new MoveAdvice(forecast.value() / 20 + utility.score() * 0.15, false, false,
                    utility.reason() + (forecast.next().isEmpty() ? "" : "; then " + forecast.next()));
        }
        double damage = damage(own, foe, move, tile.getElementalType(), 0.925D);
        double minimum = damage(own, foe, move, tile.getElementalType(), 0.85D);
        boolean unknownHp = unknownRaidHp(foe);
        boolean canHitNow = (!moveState.protectedNow(foe.uuid()) || effects(move).has("breaksProtect"))
                && (!charging(own, move) || moveState.hasEffect(own.uuid(), "charging"))
                && (!moveState.hasEffect(foe.uuid(), "substitute") || own.ability.equals("infiltrator") || effects(move).has("sound"));
        boolean ko = canHitNow && BattleMatchupPolicy.normalHitKo(minimum, foe.hp, !unknownHp);
        boolean firstKo = ko && accuracy >= 1 && first && !own.status.equals("slp") && !own.status.equals("frz") && !own.status.equals("par");
        JsonObject effect = effects(move);
        double recoil = Math.min(foe.hp, damage) * fraction(effect, "recoil");
        if (own.ability.equals("rockhead") || own.ability.equals("magicguard")) recoil = 0;
        if (own.item.endsWith("lifeorb") && !own.ability.equals("magicguard") && !(own.ability.equals("sheerforce") && effect.has("secondary"))) recoil += own.maxHp / 10;
        if (effect.has("selfdestruct")) recoil = own.hp;
        if (id(move.getName()).equals("struggle")) recoil = own.maxHp / 4;
        double drain = Math.min(own.maxHp - own.hp, Math.min(foe.hp, damage) * fraction(effect, "drain"));
        if (foe.ability.equals("liquidooze")) { recoil += drain; drain = 0; }
        firstKo &= BattleMatchupPolicy.survivesAction(own.hp, own.maxHp, 0, drain, recoil);
        double score = BattleMatchupPolicy.attackScore(damage / Math.max(1, foe.hp), accuracy, ko, firstKo,
                drain / own.maxHp, recoil / own.maxHp, before >= own.hp, recoil >= own.hp);
        if ((id(move.getName()).equals("rapidspin") || id(move.getName()).equals("mortalspin"))
                && damage > 0 && hasHazards()) score += 0.8D;
        String reason = ko ? "minimum normal hit KOs; " + Math.round(accuracy * 100) + "% accuracy"
                : unknownHp ? "raid HP unknown; ranked by expected damage"
                : canHitNow && damage(own, foe, move, tile.getElementalType(), 1) >= foe.hp
                    ? "KO needs a high damage roll; " + Math.round(accuracy * 100) + "% accuracy"
                    : "expected damage " + Math.round(100 * damage / foe.maxHp * accuracy) + "%";
        if (firstKo) reason += "; before enemy moves";
        if (recoil > 0) reason += "; recoil " + Math.round(100 * recoil / own.maxHp) + "%";
        if (drain > 0) reason += "; heals " + Math.round(100 * drain / own.maxHp) + "%";
        if (!unknownHp && !forecast.next().isEmpty()) reason += "; then " + forecast.next();
        boolean needsCharge = charging(own, move) && !moveState.hasEffect(own.uuid(), "charging");
        boolean canFinish = BattleMatchupPolicy.survivesAction(own.hp, own.maxHp, before, drain, recoil)
                && !own.status.equals("slp") && !own.status.equals("frz")
                && (!needsCharge || own.hp > incoming + before + Math.max(0, residual(own)));
        double chance = canHitNow && !unknownHp ? koChance(own, foe, move, tile.getElementalType(), accuracy) : 0;
        double actionChance = own.status.equals("par") ? 0.75 : 1;
        if (chance > 0 && !ko) reason = "normal-hit KO chance " + Math.round(100 * chance) + "%; " + reason;
        double damagePerTurn = damage;
        if (moveState.protectedNow(foe.uuid()) && !effect.has("breaksProtect")) damagePerTurn = 0;
        if (needsCharge || effect.has("recharge")) damagePerTurn /= 2;
        double value = BattleMatchupPolicy.moveValue(forecast.value(), score, damagePerTurn / Math.max(1, foe.maxHp),
                accuracy * actionChance, chance * actionChance, canFinish, unknownHp);
        if (chance == 0 && !forecast.survives()) value -= 100;
        return new MoveAdvice(value, ko, firstKo, reason, accuracy, ko && accuracy >= 1 && canFinish && actionChance >= 1);
    }

    private static MoveAdvice utility(Fighter own, Fighter foe, MoveTemplate move, double accuracy, double incoming, double before) {
        JsonObject effect = effects(move);
        String name = id(move.getName());
        double residual = residual(own);
        if (before >= own.hp) return new MoveAdvice(-6, false, false, "may faint before acting");
        if (Set.of("protect", "detect", "kingsshield", "spikyshield", "banefulbunker", "silktrap", "burningbulwark").contains(name)) {
            return new MoveAdvice(moveState.lastMove(own.uuid()).equals(name) ? -1 : 0.4, false, false,
                    moveState.lastMove(own.uuid()).equals(name) ? "repeated protection is unreliable" : "block a hit while residual effects resolve");
        }
        if (name.equals("substitute")) return new MoveAdvice(0.2, false, false, "trade HP for a substitute");
        double healing = healing(own, move);
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
        if (own.own == null) return enemyMoves(own.live).stream().anyMatch(move -> damaging(move)
                && (move.getDamageCategory() == DamageCategories.INSTANCE.getSPECIAL()) == (stat == Stats.SPECIAL_ATTACK));
        for (Move move : own.own.getMoveSet()) {
            if (move != null && move.getCurrentPp() > 0 && damaging(move.getTemplate())
                    && (move.getDamageCategory() == DamageCategories.INSTANCE.getSPECIAL()) == (stat == Stats.SPECIAL_ATTACK)) return true;
        }
        return false;
    }

    private static boolean statusBlocked(Fighter foe, String status, JsonObject effect) {
        if (grounded(foe) && (field.terrain.equals("mistyterrain") || status.equals("slp") && field.terrain.equals("electricterrain"))) return true;
        if (!foe.entering && moveState.hasEffect(foe.uuid(), "substitute")) return true;
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
        return damage(attacker, defender, move, type, roll, 0);
    }

    private static double damage(Fighter attacker, Fighter defender, MoveTemplate move, ElementalType type, double roll, int hits) {
        double result = rawDamage(attacker, defender, move, type, roll, hits)
                * observations.damageFactor(new BattleObservations.DamageKey(attacker.uuid(), defender.uuid(), id(move.getName()), damageContext(attacker, defender)));
        // Calibration cannot remove an intact Sash/Sturdy survival guarantee.
        return protectedDamage(result, attacker, defender, hits > 0 ? hits : effects(move).has("multihit") ? 2 : 1);
    }

    private static double rawDamage(Fighter attacker, Fighter defender, MoveTemplate move, ElementalType type, double roll) {
        return rawDamage(attacker, defender, move, type, roll, 0);
    }

    private static BattleDamageModifiers.User damageUser(Fighter user) {
        return new BattleDamageModifiers.User(user.ability, user.item, user.status, user.hp / user.maxHp, field.weather);
    }

    private static BattleDamageModifiers.Move damageMove(MoveTemplate move, ElementalType type, double power) {
        return new BattleDamageModifiers.Move(id(move.getName()), type.showdownId(), move.getElementalType().showdownId(), power,
                move.getDamageCategory() == DamageCategories.INSTANCE.getSPECIAL(), effects(move).keySet());
    }

    private static double rawDamage(Fighter attacker, Fighter defender, MoveTemplate move, ElementalType type, double roll, int forcedHits) {
        if (!damaging(move)) return 0;
        JsonObject effect = effects(move);
        String moveId = id(move.getName());
        type = moveType(attacker, move, type);
        double typeEffectiveness = typeEffectiveness(defender, type, moveId);
        double effectiveness = BattleMatchupPolicy.effectiveMultiplier(typeEffectiveness, type.showdownId(),
                field.gravity && defender.ability.equals("levitate") ? "" : defender.ability, attacker.ability);
        if (defender.ability.equals("wonderguard") && typeEffectiveness <= 1 && !bypassesAbility(attacker)) return 0;
        if (BattleDamageModifiers.immune(defender.ability, damageMove(move, type, move.getPower()), bypassesAbility(attacker))) return 0;
        if (effectiveness == 0) return 0;
        if (effect.has("damage")) {
            JsonElement fixed = effect.get("damage");
            double result = fixed.getAsString().equals("level") ? attacker.level() : fixed.getAsDouble();
            return protectedDamage(result, attacker, defender, 1);
        }
        if (effect.has("ohko")) {
            if (defender.ability.equals("sturdy") && !bypassesAbility(attacker)) return 0;
            return attacker.level() >= defender.level() ? protectedDamage(defender.hp, attacker, defender, 1) : 0;
        }
        if (List.of("superfang", "naturesmadness", "ruination").contains(moveId)) return Math.max(1, defender.hp / 2);
        if (moveId.equals("endeavor")) return Math.max(0, defender.hp - attacker.hp);
        if (List.of("counter", "mirrorcoat", "metalburst").contains(moveId)) return 0; // Reactive damage is not guaranteed.
        double power = move.getPower();
        if (moveId.equals("lowkick") || moveId.equals("grassknot")) {
            double weight = defender.own != null ? defender.own.getForm().getWeight()
                    : defender.live.getSpecies().getForm(defender.live.getState().getCurrentAspects()).getWeight();
            power = weight < 10 ? 20 : weight < 25 ? 40 : weight < 50 ? 60 : weight < 100 ? 80 : weight < 200 ? 100 : 120;
        } else if (power <= 0) {
            power = roll == 1 ? 120 : 40; // Conservative bounds for other variable-power attacks.
        }
        String name = id(move.getName());
        var user = damageUser(attacker);
        var target = damageUser(defender);
        power = BattleDamageModifiers.power(user, target, damageMove(move, type, power));
        if (name.equals("weatherball") && !field.weather.isEmpty()) power *= 2;
        if (name.equals("gyroball")) power = Math.min(150, Math.floor(25 * defender.stat(Stats.SPEED) / attacker.stat(Stats.SPEED)) + 1);
        if (name.equals("electroball")) {
            double ratio = attacker.stat(Stats.SPEED) / defender.stat(Stats.SPEED);
            power = ratio >= 4 ? 150 : ratio >= 3 ? 120 : ratio >= 2 ? 80 : ratio >= 1 ? 60 : 40;
        }
        if (name.equals("storedpower") || name.equals("powertrip")) {
            int boosts = 0;
            for (Stats stat : Stats.values()) if (stat != Stats.HP) boosts += Math.max(0, attacker.stage(stat));
            power = 20 + 20 * boosts;
        }
        if (attacker.own != null && (name.equals("return") || name.equals("frustration"))) {
            power = Math.max(1, Math.floor((name.equals("return") ? attacker.own.getFriendship() : 255 - attacker.own.getFriendship()) * 2.0 / 5));
        }
        var moveInfo = damageMove(move, type, power);
        power *= BattleDamageModifiers.powerFactor(user, moveInfo);
        boolean special = move.getDamageCategory() == DamageCategories.INSTANCE.getSPECIAL();
        double attack = attacker.stat(special ? Stats.SPECIAL_ATTACK : Stats.ATTACK);
        if (name.equals("facade") && attacker.status.equals("brn") && !attacker.ability.equals("guts")) attack *= 2;
        if (name.equals("bodypress")) attack = attacker.stat(Stats.DEFENCE);
        if (name.equals("foulplay")) attack = defender.stat(Stats.ATTACK);
        boolean physicalDefence = !special || name.equals("psyshock") || name.equals("psystrike") || name.equals("secretsword");
        Stats defenceStat = physicalDefence ? Stats.DEFENCE : Stats.SPECIAL_DEFENCE;
        double defence = defender.stat(defenceStat) * BattleDamageModifiers.defenceFactor(target, physicalDefence, bypassesAbility(attacker));
        boolean critical = effect.has("guaranteedCrit") && (bypassesAbility(attacker)
                || !Set.of("battlearmor", "shellarmor").contains(defender.ability));
        if (critical && defender.stage(defenceStat) > 0) defence /= BattleMatchupPolicy.stageMultiplier(defender.stage(defenceStat));
        Stats attackStat = special ? Stats.SPECIAL_ATTACK : Stats.ATTACK;
        if (critical && attacker.stage(attackStat) < 0) attack /= BattleMatchupPolicy.stageMultiplier(attacker.stage(attackStat));
        attack *= BattleDamageModifiers.attackFactor(user, moveInfo);
        double stab = hasType(attacker, type.showdownId()) ? attacker.ability.equals("adaptability") ? 2 : 1.5D : 1;
        double hits = forcedHits > 0 ? forcedHits : 1;
        if (forcedHits <= 0 && effect.has("multihit")) {
            JsonElement multi = effect.get("multihit");
            hits = multi.isJsonArray() ? (attacker.ability.equals("skilllink") || roll == 1 ? multi.getAsJsonArray().get(1).getAsDouble()
                    : roll == 0.85D ? multi.getAsJsonArray().get(0).getAsDouble() : attacker.item.endsWith("loadeddice") ? 4.5 : 3.1) : multi.getAsDouble();
        }
        double result = (((2.0D * attacker.level() / 5 + 2) * power * attack / defence / 50) + 2)
                * stab * effectiveness * roll * hits;
        if (critical) result *= attacker.ability.equals("sniper") ? 2.25 : 1.5;
        if (attacker.ability.equals("solarpower") && special && field.weather.equals("sunnyday")) result *= 1.5;
        result *= field.weatherDamage(type.showdownId()) * field.terrainDamage(type.showdownId(), name,
                grounded(attacker), grounded(defender), priority(attacker, move));
        BattleFieldState.Side side = defender.allied ? field.ally : field.opponent;
        if (!critical && !attacker.ability.equals("infiltrator") && !name.equals("brickbreak") && !name.equals("psychicfangs")
                && (side.auroraVeil || special && side.lightScreen || !special && side.reflect)) {
            result *= activePokemon().size() > 2 ? 2.0 / 3 : 0.5;
        }
        if (attacker.item.endsWith("lifeorb")) result *= 1.3D;
        result *= BattleDamageModifiers.damageFactor(user, target, moveInfo, typeEffectiveness, bypassesAbility(attacker));
        return protectedDamage(result, attacker, defender, hits);
    }

    private static double koChance(Fighter own, Fighter foe, MoveTemplate move, ElementalType type, double accuracy) {
        JsonElement multihit = effects(move).get("multihit");
        if (multihit == null || !multihit.isJsonArray()) {
            return BattleMatchupPolicy.normalKoChance(damage(own, foe, move, type, 0.85), damage(own, foe, move, type, 1), foe.hp, accuracy);
        }
        int min = multihit.getAsJsonArray().get(0).getAsInt(), max = multihit.getAsJsonArray().get(1).getAsInt();
        if (own.ability.equals("skilllink")) min = max;
        else if (own.item.endsWith("loadeddice")) min = Math.max(min, 4);
        double result = 0;
        for (int hits = min; hits <= max; hits++) {
            double weight = min == 2 && max == 5 ? hits <= 3 ? 0.35 : 0.15 : 1.0 / (max - min + 1);
            result += weight * BattleMatchupPolicy.normalKoChance(damage(own, foe, move, type, 0.85, hits),
                    damage(own, foe, move, type, 1, hits), foe.hp, accuracy);
        }
        return result;
    }

    private static boolean bypassesAbility(Fighter attacker) {
        return List.of("moldbreaker", "teravolt", "turboblaze").contains(attacker.ability);
    }

    private static double protectedDamage(double result, Fighter attacker, Fighter defender, double hits) {
        if (hits == 1 && defender.hp >= defender.maxHp && (defender.item.endsWith("focussash")
                || defender.ability.equals("sturdy") && !bypassesAbility(attacker))) result = Math.min(result, defender.hp - 1);
        return Math.max(0, result);
    }

    private static ElementalType moveType(Fighter attacker, MoveTemplate move, ElementalType type) {
        String moveId = id(move.getName());
        JsonObject effect = effects(move);
        boolean changesType = BattleDamageModifiers.changesType(moveId);
        if (changesType) type = ElementalTypes.getOrException(BattleDamageModifiers.type(type.showdownId(), attacker.ability));
        if (attacker.ability.equals("liquidvoice") && effect.has("sound")) type = ElementalTypes.WATER;
        if (moveId.equals("weatherball")) type = switch (field.weather) {
            case "sunnyday" -> ElementalTypes.FIRE;
            case "raindance" -> ElementalTypes.WATER;
            case "sandstorm" -> ElementalTypes.ROCK;
            case "hail", "snow" -> ElementalTypes.ICE;
            default -> type;
        };
        return type;
    }

    private static double multiplier(Fighter attacker, Fighter defender, MoveTemplate move, ElementalType type) {
        type = moveType(attacker, move, type);
        double base = typeEffectiveness(defender, type, id(move.getName()));
        if (BattleDamageModifiers.immune(defender.ability, damageMove(move, type, move.getPower()), bypassesAbility(attacker))) return 0;
        if (defender.ability.equals("wonderguard") && base <= 1 && !bypassesAbility(attacker)) return 0;
        return BattleMatchupPolicy.effectiveMultiplier(base, type.showdownId(),
                field.gravity && defender.ability.equals("levitate") ? "" : defender.ability, attacker.ability);
    }

    private static double typeEffectiveness(Fighter defender, ElementalType type, String move) {
        List<ElementalType> types = field.gravity && type.showdownId().equals("ground")
                ? defender.types.stream().filter(t -> !t.showdownId().equals("flying")).toList() : defender.types;
        double base = CobblemonTeamAdvisor.moveMultiplier(type, types);
        if (move.equals("freezedry") && hasType(defender, "water")) base *= 4;
        if (move.equals("flyingpress")) base *= CobblemonTeamAdvisor.moveMultiplier(ElementalTypes.FLYING, defender.types);
        return base;
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
        boolean magicGuard = own.ability.equals("magicguard");
        double fraction = magicGuard ? 0 : switch (own.status) {
            case "brn" -> 1.0D / 16;
            case "psn", "tox" -> own.ability.equals("poisonheal") ? -1.0D / 8 : 1.0D / 8;
            default -> 0;
        };
        if (own.item.endsWith("leftovers") || own.item.endsWith("blacksludge") && hasType(own, "poison")) fraction -= 1.0D / 16;
        if (grounded(own) && field.terrain.equals("grassyterrain")) fraction -= 1.0 / 16;
        boolean weatherImmune = magicGuard || own.ability.equals("overcoat") || own.item.endsWith("safetygoggles");
        if (!weatherImmune && field.weather.equals("sandstorm") && !hasType(own, "rock") && !hasType(own, "ground") && !hasType(own, "steel")
                && !List.of("sandforce", "sandrush", "sandveil").contains(own.ability)) fraction += 1.0 / 16;
        if (!weatherImmune && field.weather.equals("hail") && !hasType(own, "ice") && !own.ability.equals("icebody")) fraction += 1.0 / 16;
        if (field.weather.equals("raindance") && own.ability.equals("raindish")) fraction -= 1.0 / 16;
        if ((field.weather.equals("snow") || field.weather.equals("hail")) && own.ability.equals("icebody")) fraction -= 1.0 / 16;
        if (own.ability.equals("dryskin")) {
            if (field.weather.equals("raindance")) fraction -= 1.0 / 8;
            if (field.weather.equals("sunnyday")) fraction += 1.0 / 8;
        }
        if (field.weather.equals("sunnyday") && own.ability.equals("solarpower")) fraction += 1.0 / 8;
        return own.maxHp * fraction;
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
        hint(graphics, hint, (raid(opponent) ? "Raid: boss HP unknown" : CobblemonTeamAdvisor.opponentMoves(opponent).source()) + "; damage / speed are estimates");
    }

    static void renderSwitchHint(GuiGraphics graphics, BattleSwitchPokemonSelection.SwitchTile tile, ClientBattlePokemon opponent) {
        if (tile == null || opponent == null) return;
        hint(graphics, "Best switch: " + tile.getPokemon().getDisplayName(false).getString() + " — "
                + matchup(tile.getPokemon(), tile.getShowdownPokemon(), opponent, false).reason(),
                (raid(opponent) ? "Raid: boss HP unknown" : CobblemonTeamAdvisor.opponentMoves(opponent).source()) + "; damage / speed are estimates");
    }

    private static void hint(GuiGraphics graphics, String first, String second) {
        Minecraft minecraft = Minecraft.getInstance();
        int width = Math.max(60, minecraft.getWindow().getGuiScaledWidth() - 80);
        graphics.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(first, width), 6, 6, 0xFFB8FFD0, true);
        graphics.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(second, width), 6, 17, 0xFFE8E8E8, true);
    }
}
