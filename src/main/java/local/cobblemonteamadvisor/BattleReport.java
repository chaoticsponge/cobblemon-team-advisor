package local.cobblemonteamadvisor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evidence-based review of a single client trace; hidden mechanics are never invented. */
final class BattleReport {
    private final Map<String, JsonObject> pokemon = new LinkedHashMap<>();
    private final List<JsonObject> notable = new ArrayList<>();
    private final Set<String> faints = new HashSet<>();
    private final JsonObject counters = new JsonObject();
    private JsonObject context = new JsonObject(), result;
    private JsonObject pendingKo;
    private String movingPokemon = "";
    private boolean koAction;
    private Double koHealth;
    private int turns, automated;
    private int lastSwitchTurn = -1, lastAllyMove = -1;
    private final Map<String, Integer> switchTurns = new LinkedHashMap<>();

    void accept(String type, JsonObject data, int turn, long sequence) {
        turns = Math.max(turns, turn);
        if (type.equals("battle_start")) context = data.deepCopy();
        if (type.equals("battle_snapshot")) {
            var rosters = object(context, "assumed_rosters").deepCopy();
            object(data, "assumed_rosters").entrySet().forEach(entry -> rosters.add(entry.getKey(), entry.getValue().deepCopy()));
            context.add("assumed_rosters", rosters);
            context.add("last_field", object(data, "field").deepCopy());
            for (var member : array(data, "pokemon")) {
                JsonObject snapshot = member.getAsJsonObject();
                String id = text(snapshot, "pokemon");
                if (id.isEmpty()) continue;
                JsonObject known = pokemon.computeIfAbsent(id, ignored -> new JsonObject());
                snapshot.entrySet().forEach(e -> known.add(e.getKey(), e.getValue().deepCopy()));
            }
        }
        if (type.startsWith("auto_") && (type.endsWith("submitted") || type.equals("auto_item_requested"))) {
            count(type); automated++;
        }
        if (type.equals("auto_switch_submitted")) {
            switchTurns.put(text(data, "pokemon"), turn);
            if (!data.has("forced") || !data.get("forced").getAsBoolean()) {
                count("voluntary_auto_switches");
                if (lastSwitchTurn >= 0 && lastAllyMove < lastSwitchTurn) {
                    count("switches_without_an_attack"); note("switches_without_an_attack", data, turn, sequence);
                }
                lastSwitchTurn = turn;
            }
        }
        if (type.equals("pokemon_entered")) note(type, data, turn, sequence);
        if (type.equals("auto_move_submitted")) {
            finishKo(turn, sequence);
            pendingKo = null; koAction = false; koHealth = null;
            JsonObject advice = object(data, "advice");
            if (advice.has("ko") && advice.get("ko").getAsBoolean()) {
                pendingKo = data.deepCopy(); pendingKo.addProperty("submitted_sequence", sequence);
            }
        }
        if (type.equals("move_used")) {
            finishKo(turn, sequence);
            movingPokemon = text(data, "pokemon");
            koAction = pendingKo != null && movingPokemon.equals(text(pendingKo, "pokemon"));

            if (movingPokemon.isEmpty()) { count("unattributed_moves"); note("unattributed_move", data, turn, sequence); return; }
            JsonObject known = pokemon.computeIfAbsent(movingPokemon, ignored -> new JsonObject());
            known.addProperty("pokemon", movingPokemon);
            JsonArray moves = known.has("moves_used") ? known.getAsJsonArray("moves_used") : new JsonArray();
            if (!moves.contains(data.get("move"))) moves.add(data.get("move"));
            known.add("moves_used", moves);
            count(side(movingPokemon) + "_moves_used");
            if (side(movingPokemon).equals("ally")) lastAllyMove = turn;
        }
        if (type.equals("ability_event")) {
            count("ability_events"); note(type, data, turn, sequence);
            for (var ref : object(data, "pokemon_refs").entrySet()) {
                JsonObject known = pokemon.computeIfAbsent(ref.getValue().getAsString(), ignored -> new JsonObject());
                known.addProperty("pokemon", ref.getValue().getAsString());
                JsonArray events = known.has("ability_events") ? known.getAsJsonArray("ability_events") : new JsonArray();
                JsonObject event = data.deepCopy(); event.addProperty("sequence", sequence); event.addProperty("turn", turn);
                events.add(event); known.add("ability_events", events);
            }
        }
        if (type.equals("health")) {
            String id = text(data, "pokemon");
            JsonElement health = data.get("after_fraction");
            if (id.isEmpty() || health == null || !health.isJsonPrimitive() || !health.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(health.getAsDouble())) return;
            JsonObject known = pokemon.computeIfAbsent(id, ignored -> new JsonObject());
            known.addProperty("pokemon", id);
            known.add("last_observed_hp_fraction", health);
            double before = number(data, "before_fraction"), after = number(data, "after_fraction");
            if (before > after) {
                count(side(id) + "_damage_events");
                JsonObject damage = data.deepCopy();
                damage.addProperty("last_move_user", movingPokemon);
                damage.addProperty("attribution", "chronological context only; damage may be residual, recoil or another effect");
                known.addProperty("cumulative_hp_fraction_lost", number(known, "cumulative_hp_fraction_lost") + before - after);
                if (switchTurns.getOrDefault(id, -1) == turn) {
                    count("damage_after_auto_switch"); note("damage_after_auto_switch", damage, turn, sequence);
                }
                if (after <= 0) faint(id, damage, turn, sequence);
            }
            if (koAction && pendingKo != null && id.equals(text(pendingKo, "opponent"))) koHealth = after;
        }
        if (type.equals("message")) {
            String key = text(data, "key");
            var args = array(data, "args");
            String allies = text(context, "allies"), enemies = text(context, "enemies");
            if (!allies.isEmpty() && !enemies.isEmpty() && !allies.equals(enemies) && args.size() == 1 && result == null) {
                Boolean won = TrainerBattleStats.outcome(key, args.get(0).getAsString(), allies, enemies);
                if (won != null) {
                    result = data.deepCopy(); result.addProperty("outcome", won ? "win" : "loss");
                    result.addProperty("turn", turn); result.addProperty("sequence", sequence);
                    finishKo(turn, sequence);
                }
            }
            if (key.equals("cobblemon.battle.turn")) { finishKo(turn, sequence); pendingKo = null; koAction = false; koHealth = null; movingPokemon = ""; }
            String kind = key.equals("cobblemon.battle.fainted") ? "faint"
                    : key.equals("cobblemon.battle.missed") ? "miss"
                    : key.startsWith("cobblemon.battle.fail") ? "failed_action"
                    : key.startsWith("cobblemon.battle.cant.") ? "prevented_action"
                    : key.equals("cobblemon.battle.immune") ? "immunity"
                    : key.contains("crit") ? "critical_hit" : "";
            if (!kind.isEmpty()) {
                if (kind.equals("faint")) {
                    String id = text(object(data, "pokemon_refs"), "0");
                    if (!id.isEmpty()) faint(id, data, turn, sequence);
                    else note("unattributed_faint", data, turn, sequence);
                } else { count(kind); note(kind, data, turn, sequence); }
            }
        }
        if (type.equals("invalid_choice")) { count(type); note(type, data, turn, sequence); }
        if (type.equals("battle_closed")) finishKo(turn, sequence);
    }

    private void faint(String id, JsonObject evidence, int turn, long sequence) {
        if (!faints.add(turn + ":" + id)) return; // A zero-HP packet and faint message describe one event.
        count(side(id) + "_faints");
        JsonObject data = evidence.deepCopy(); data.addProperty("pokemon", id);
        note("faint", data, turn, sequence);
    }

    private void finishKo(int turn, long sequence) {
        if (koAction && pendingKo != null) {
            if (koHealth != null && koHealth > 0) {
                JsonObject evidence = pendingKo.deepCopy(); evidence.addProperty("observed_target_hp_fraction", koHealth);
                evidence.addProperty("interpretation", "Predicted KO was not observed during this move; review damage estimates, mitigation and battle messages.");
                count("ko_prediction_not_confirmed"); note("ko_prediction_not_confirmed", evidence, turn, sequence);
            }
            pendingKo = null; koAction = false; koHealth = null;
        }
    }

    private String side(String id) { return text(pokemon.getOrDefault(id, new JsonObject()), "side"); }
    private void count(String key) { counters.addProperty(key, number(counters, key) + 1); }
    private void note(String type, JsonObject data, int turn, long sequence) {
        JsonObject event = new JsonObject(); event.addProperty("event", type); event.addProperty("turn", turn);
        event.addProperty("sequence", sequence); event.add("data", data.deepCopy()); notable.add(event);
    }

    boolean completed() { return result != null; }
    String outcome() { return result == null ? "unresolved" : text(result, "outcome"); }
    String mode() { return automated == 0 ? "manual" : "auto_assisted"; }
    JsonObject summary(boolean closed) {
        JsonObject report = new JsonObject(); report.addProperty("schema_version", 2);
        report.addProperty("outcome", outcome()); report.addProperty("state", closed ? "closed" : completed() ? "result_received" : "in_progress");
        report.addProperty("control", mode()); report.addProperty("turns_observed", turns);
        report.add("context", context.deepCopy()); report.add("result_evidence", result == null ? null : result.deepCopy());
        report.add("counts", counters.deepCopy());
        JsonArray teams = new JsonArray(); pokemon.values().forEach(p -> teams.add(p.deepCopy())); report.add("pokemon", teams);
        JsonObject grouped = new JsonObject();
        for (String side : List.of("ally", "enemy", "unknown")) {
            JsonArray members = new JsonArray();
            pokemon.values().stream().filter(p -> text(p, "side").equals(side) || side.equals("unknown") && text(p, "side").isEmpty())
                    .forEach(p -> members.add(p.deepCopy()));
            grouped.add(side, members);
        }
        report.add("teams", grouped);
        JsonArray events = new JsonArray(); notable.forEach(e -> events.add(e.deepCopy())); report.add("review_events", events);
        JsonArray findings = new JsonArray();
        findings.add(completed() ? "Confirmed " + outcome() + " from the server's battle result message."
                : "No confirmed win/loss message; do not count this as a defeat.");
        for (String key : List.of("ally_faints", "enemy_faints", "miss", "failed_action", "prevented_action", "immunity", "invalid_choice", "voluntary_auto_switches", "switches_without_an_attack", "damage_after_auto_switch", "ko_prediction_not_confirmed", "critical_hit", "ability_events", "unattributed_moves"))
            if (number(counters, key) > 0) findings.add(key + ": " + (int) number(counters, key) + "; inspect linked turn/sequence evidence.");
        findings.add("These are observed contributing events, not a proven counterfactual explanation of the outcome. Hidden data remains unknown; assumed roster data is labeled separately.");
        report.add("findings", findings);
        return report;
    }

    static String text(JsonObject data, String key) { return data.has(key) && data.get(key).isJsonPrimitive() ? data.get(key).getAsString() : ""; }
    static double number(JsonObject data, String key) { return data.has(key) && data.get(key).isJsonPrimitive() ? data.get(key).getAsDouble() : 0; }
    static JsonArray array(JsonObject data, String key) { return data.has(key) && data.get(key).isJsonArray() ? data.getAsJsonArray(key) : new JsonArray(); }
    static JsonObject object(JsonObject data, String key) { return data.has(key) && data.get(key).isJsonObject() ? data.getAsJsonObject(key) : new JsonObject(); }
}
