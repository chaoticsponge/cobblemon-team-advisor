package local.cobblemonteamadvisor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reference rules: Cobblemon does not sync evolution requirements to multiplayer clients. */
final class EvolutionGuide {
    record Entry(String target, String requirements) { }
    record Result(boolean known, List<Entry> entries) { }
    private final JsonObject species;
    private final JsonObject names;

    private EvolutionGuide(JsonObject root) {
        species = root.getAsJsonObject("species");
        names = root.getAsJsonObject("names");
    }

    static EvolutionGuide load() {
        try (var stream = EvolutionGuide.class.getResourceAsStream("/pokedex-evolutions.json")) {
            if (stream == null) throw new IllegalStateException("Missing evolution guide");
            return new EvolutionGuide(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read evolution guide", error);
        }
    }

    Result next(String speciesId, String form) {
        if (!species.has(speciesId)) return new Result(false, List.of());
        JsonObject definition = species.getAsJsonObject(speciesId);
        if (!form.equalsIgnoreCase("normal") && !form.equalsIgnoreCase("standard")) {
            JsonObject selected = null;
            for (JsonElement value : array(definition, "forms")) {
                JsonObject candidate = value.getAsJsonObject();
                if (text(candidate, "name").equalsIgnoreCase(form)) { selected = candidate; break; }
            }
            // A custom server form must not accidentally inherit the standard form's evolution.
            if (selected == null) return new Result(false, List.of());
            definition = selected;
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonElement value : array(definition, "evolutions")) {
            JsonObject evolution = value.getAsJsonObject();
            List<String> conditions = new ArrayList<>();
            String trigger = switch (text(evolution, "variant")) {
                case "level_up", "passive" -> "Level up";
                case "item_interact" -> "Use " + item(evolution.get("requiredContext"));
                case "trade" -> "Trade" + (evolution.has("requiredContext") ? " for " + properties(text(evolution, "requiredContext")) : "");
                case "block_click" -> "Interact with " + readable(text(evolution, "requiredContext"));
                default -> readable(text(evolution, "variant"));
            };
            conditions.add(trigger);
            for (JsonElement requirement : array(evolution, "requirements")) conditions.add(requirement(requirement.getAsJsonObject()));
            if (evolution.has("consumeHeldItem") && evolution.get("consumeHeldItem").getAsBoolean()) conditions.add("held item consumed");
            Entry entry = new Entry(properties(text(evolution, "result")), String.join("; ", conditions));
            if (!entries.contains(entry)) entries.add(entry);
            if (evolution.has("shedder")) entries.add(new Entry(properties(text(evolution, "shedder")),
                    "Alongside " + entry.target() + "; " + entry.requirements() + "; empty party slot; a Poké Ball in inventory"));
        }
        return new Result(true, List.copyOf(entries));
    }

    private String requirement(JsonObject rule) {
        String amount = text(rule, "amount");
        return switch (text(rule, "variant")) {
            case "level" -> "Lv. " + (rule.has("minLevel") ? text(rule, "minLevel") : "1")
                    + (rule.has("maxLevel") ? "–" + text(rule, "maxLevel") : "+");
            case "friendship" -> "Friendship ≥ " + amount;
            case "held_item" -> "Hold " + item(rule.get("itemCondition"));
            case "has_move" -> "Know " + move(text(rule, "move"));
            case "has_move_type" -> "Know a " + readable(text(rule, "type")) + " move";
            case "use_move" -> "Use " + move(text(rule, "move")) + " " + amount + " times";
            case "battle_critical_hits" -> "Land " + amount + " critical hits in one battle";
            case "damage_taken" -> "Take " + amount + " damage";
            case "recoil" -> "Take " + amount + " recoil damage";
            case "blocks_traveled" -> "Travel " + amount + " blocks with Pokémon out";
            case "defeat" -> "Defeat " + amount + " × " + properties(text(rule, "target"));
            case "party_member" -> (rule.has("contains") && !rule.get("contains").getAsBoolean() ? "No " : "Party: ")
                    + properties(text(rule, "target"));
            case "properties" -> properties(text(rule, "target"));
            case "property_range" -> readable(text(rule, "feature")) + ": " + text(rule, "range");
            case "time_range" -> "Time: " + readable(text(rule, "range"));
            case "moon_phase" -> readable(text(rule, "moonPhase"));
            case "biome" -> location(rule, "biome");
            case "structure" -> location(rule, "structure");
            case "weather" -> "Weather: " + (rule.has("isRaining") ? rule.get("isRaining").getAsBoolean() ? "Rain" : "No rain" : "Special weather condition");
            case "stat_compare" -> readable(text(rule, "highStat")) + " > " + readable(text(rule, "lowStat"));
            case "stat_equal" -> readable(text(rule, "statOne")) + " = " + readable(text(rule, "statTwo"));
            case "advancement" -> "Advancement: " + readable(text(rule, "requiredAdvancement"));
            case "any" -> "Any of: " + String.join(" or ", array(rule, "possibilities").asList().stream()
                    .map(value -> requirement(value.getAsJsonObject())).toList());
            default -> "Special condition: " + readable(text(rule, "variant"));
        };
    }

    private static String location(JsonObject rule, String kind) {
        List<String> parts = new ArrayList<>();
        for (String suffix : List.of("Condition", "Anticondition")) {
            String value = text(rule, kind + suffix);
            if (value.isEmpty()) continue;
            String label = readable(value);
            if (value.contains("/regional/")) {
                label = readable(value.substring(value.lastIndexOf('_') + 1).replace("biome", "")) + " evolution biomes";
            }
            parts.add((suffix.equals("Anticondition") ? "Outside " : "In ") + label);
        }
        return String.join(", ", parts);
    }

    private String properties(String value) {
        List<String> parts = new ArrayList<>();
        for (String token : value.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (!token.contains("=")) {
                String id = token.contains(":") ? token.substring(token.indexOf(':') + 1) : token;
                String key = "cobblemon.species." + id + ".name";
                parts.add(names.has(key) ? names.get(key).getAsString() : readable(token));
            } else {
                String[] pair = token.split("=", 2);
                parts.add(pair[0].equals("held_item") ? "holding " + readable(pair[1])
                        : readable(pair[0]) + ": " + readable(pair[1]));
            }
        }
        return String.join(" · ", parts);
    }

    private String move(String value) {
        String key = "cobblemon.move." + value;
        return names.has(key) ? names.get(key).getAsString() : readable(value);
    }

    private static String item(JsonElement value) {
        if (value == null || value.isJsonNull()) return "the required item";
        if (value.isJsonPrimitive()) return readable(value.getAsString());
        JsonObject predicate = value.getAsJsonObject();
        if (predicate.has("items")) {
            JsonElement items = predicate.get("items");
            return items.isJsonArray() ? String.join(" or ", items.getAsJsonArray().asList().stream().map(v -> readable(v.getAsString())).toList())
                    : readable(items.getAsString());
        }
        return "an item matching the server rule";
    }

    static String readable(String value) {
        String id = value.substring(value.indexOf(':') + 1).replace('_', ' ').replace('/', ' ');
        StringBuilder result = new StringBuilder();
        for (String word : id.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }
}
