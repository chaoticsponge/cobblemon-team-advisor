package local.cobblemonteamadvisor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TrainerRoster {
    record Member(String species, int level, List<String> moveset, String nature, String ability, String heldItem,
                  Map<String, Integer> ivs, Map<String, Integer> evs, List<String> aspects) {
        String key() { return normalize(species) + ":" + level; }
        List<String> moves() { return moveset == null ? List.of() : moveset; }
    }

    private static final Map<String, List<List<Member>>> ROSTERS = load();

    static List<Member> match(String trainer, Set<String> seen, String current, List<String> revealed) {
        String name = normalize(trainer);
        List<List<Member>> teams = ROSTERS.get(name);
        if (teams == null) {
            // Prefer the longest matching full trainer name, never a species-only roster guess.
            String longest = "";
            for (String candidate : ROSTERS.keySet()) {
                if (candidate.length() >= 5 && candidate.length() > longest.length() && name.contains(candidate)) longest = candidate;
            }
            teams = ROSTERS.get(longest);
        }
        if (teams == null) return List.of();
        List<List<Member>> matches = new ArrayList<>();
        for (List<Member> team : teams) {
            Set<String> keys = team.stream().map(Member::key).collect(java.util.stream.Collectors.toSet());
            if (keys.containsAll(seen) && team.stream().anyMatch(member -> member.key().equals(current)
                    && member.moves().containsAll(revealed))) matches.add(team);
        }
        return matches.size() == 1 ? matches.getFirst() : List.of();
    }

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Map<String, List<List<Member>>> load() {
        try (var input = TrainerRoster.class.getResourceAsStream("/trainer-rosters.json")) {
            if (input == null) throw new IllegalStateException("Missing trainer rosters");
            return new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, List<List<Member>>>>() { }.getType());
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot load trainer rosters", error);
        }
    }
}
