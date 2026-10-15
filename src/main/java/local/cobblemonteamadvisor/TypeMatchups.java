package local.cobblemonteamadvisor;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TypeMatchups {
    static final List<String> TYPES = List.of("normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison",
            "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy");
    private static final Map<String, Map<String, Double>> CHART = chart();

    private TypeMatchups() { }

    static double multiplier(String attack, String defender) {
        return CHART.getOrDefault(attack, Map.of()).getOrDefault(defender, 1.0);
    }

    static double multiplier(String attack, Collection<String> defenders) {
        double result = 1;
        for (String defender : defenders) result *= multiplier(attack, defender);
        return result;
    }

    private static Map<String, Map<String, Double>> chart() {
        Map<String, Map<String, Double>> chart = new HashMap<>();
        put(chart, "normal", 0.0D, "ghost");
        put(chart, "normal", 0.5D, "rock", "steel");
        put(chart, "fire", 2.0D, "grass", "ice", "bug", "steel");
        put(chart, "fire", 0.5D, "fire", "water", "rock", "dragon");
        put(chart, "water", 2.0D, "fire", "ground", "rock");
        put(chart, "water", 0.5D, "water", "grass", "dragon");
        put(chart, "electric", 2.0D, "water", "flying");
        put(chart, "electric", 0.5D, "electric", "grass", "dragon");
        put(chart, "electric", 0.0D, "ground");
        put(chart, "grass", 2.0D, "water", "ground", "rock");
        put(chart, "grass", 0.5D, "fire", "grass", "poison", "flying", "bug", "dragon", "steel");
        put(chart, "ice", 2.0D, "grass", "ground", "flying", "dragon");
        put(chart, "ice", 0.5D, "fire", "water", "ice", "steel");
        put(chart, "fighting", 2.0D, "normal", "ice", "rock", "dark", "steel");
        put(chart, "fighting", 0.5D, "poison", "flying", "psychic", "bug", "fairy");
        put(chart, "fighting", 0.0D, "ghost");
        put(chart, "poison", 2.0D, "grass", "fairy");
        put(chart, "poison", 0.5D, "poison", "ground", "rock", "ghost");
        put(chart, "poison", 0.0D, "steel");
        put(chart, "ground", 2.0D, "fire", "electric", "poison", "rock", "steel");
        put(chart, "ground", 0.5D, "grass", "bug");
        put(chart, "ground", 0.0D, "flying");
        put(chart, "flying", 2.0D, "grass", "fighting", "bug");
        put(chart, "flying", 0.5D, "electric", "rock", "steel");
        put(chart, "psychic", 2.0D, "fighting", "poison");
        put(chart, "psychic", 0.5D, "psychic", "steel");
        put(chart, "psychic", 0.0D, "dark");
        put(chart, "bug", 2.0D, "grass", "psychic", "dark");
        put(chart, "bug", 0.5D, "fire", "fighting", "poison", "flying", "ghost", "steel", "fairy");
        put(chart, "rock", 2.0D, "fire", "ice", "flying", "bug");
        put(chart, "rock", 0.5D, "fighting", "ground", "steel");
        put(chart, "ghost", 2.0D, "psychic", "ghost");
        put(chart, "ghost", 0.5D, "dark");
        put(chart, "ghost", 0.0D, "normal");
        put(chart, "dragon", 2.0D, "dragon");
        put(chart, "dragon", 0.5D, "steel");
        put(chart, "dragon", 0.0D, "fairy");
        put(chart, "dark", 2.0D, "psychic", "ghost");
        put(chart, "dark", 0.5D, "fighting", "dark", "fairy");
        put(chart, "steel", 2.0D, "ice", "rock", "fairy");
        put(chart, "steel", 0.5D, "fire", "water", "electric", "steel");
        put(chart, "fairy", 2.0D, "fighting", "dragon", "dark");
        put(chart, "fairy", 0.5D, "fire", "poison", "steel");
        return chart;
    }

    private static void put(Map<String, Map<String, Double>> chart, String attack, double multiplier, String... defenders) {
        Map<String, Double> row = chart.computeIfAbsent(attack, key -> new HashMap<>());
        for (String defender : defenders) {
            row.put(defender, multiplier);
        }
    }

}
