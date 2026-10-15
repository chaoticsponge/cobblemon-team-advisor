package local.cobblemonteamadvisor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Bounded multi-start search over owned Pokémon; scores coverage and defensive/role balance. */
final class TeamOptimizer {
    record Candidate(String id, String species, double strength, double speed, double physical, double special,
                     double bulk, double[] offense, double[] defense) { }

    static List<String> create(List<Candidate> owned, Set<String> previous, long seed) {
        Set<String> forms = new HashSet<>();
        List<Candidate> pool = owned.stream().sorted(Comparator.comparingDouble(Candidate::strength).reversed())
                .filter(candidate -> forms.add(candidate.species + java.util.Arrays.toString(candidate.defense))).limit(160).toList();
        if (pool.isEmpty()) return List.of();
        Random random = new Random(seed);
        List<Candidate> best = List.of();
        double bestScore = -Double.MAX_VALUE;
        for (int attempt = 0; attempt < 32; attempt++) {
            List<Candidate> team = new ArrayList<>();
            team.add(pool.get(attempt == 0 ? 0 : random.nextInt(Math.min(pool.size(), 32))));
            while (team.size() < Math.min(6, owned.size())) {
                Candidate next = null;
                double nextScore = -Double.MAX_VALUE;
                for (Candidate candidate : pool) {
                    if (team.stream().anyMatch(member -> member.id.equals(candidate.id) || member.species.equals(candidate.species))) continue;
                    team.add(candidate);
                    double value = score(team);
                    team.removeLast();
                    if (value > nextScore) { nextScore = value; next = candidate; }
                }
                if (next == null) {
                    for (Candidate candidate : owned) if (team.stream().noneMatch(member -> member.id.equals(candidate.id))) {
                        if (next == null || candidate.strength > next.strength) next = candidate;
                    }
                }
                if (next == null) break;
                team.add(next);
            }
            String signature = signature(team.stream().map(Candidate::id).toList());
            double value = score(team) - (previous.contains(signature) ? 100_000 : 0);
            if (value > bestScore) { best = List.copyOf(team); bestScore = value; }
        }
        // Put a fast, well-covered general-purpose lead first.
        return best.stream().sorted(Comparator.comparingDouble((Candidate candidate) -> candidate.strength + candidate.speed * 0.1).reversed())
                .map(Candidate::id).toList();
    }

    static String signature(List<String> ids) { return String.join(",", ids.stream().sorted().toList()); }

    static double score(List<Candidate> team) {
        double score = 0, physical = 0, special = 0, bulk = 0, speed = 0;
        for (Candidate member : team) {
            score += member.strength;
            physical = Math.max(physical, member.physical); special = Math.max(special, member.special);
            bulk = Math.max(bulk, member.bulk); speed = Math.max(speed, member.speed);
        }
        for (int type = 0; type < 18; type++) {
            double coverage = 0, defense = 4;
            int weak = 0;
            for (Candidate member : team) {
                coverage = Math.max(coverage, member.offense[type]);
                defense = Math.min(defense, member.defense[type]);
                if (member.defense[type] > 1) weak++;
            }
            score += Math.sqrt(coverage) * 3 + (defense == 0 ? 8 : defense < 1 ? 5 : 0) - Math.max(0, weak - 2) * 10;
        }
        return score + Math.min(physical, special) * 0.08 + bulk * 0.03 + speed * 0.1;
    }
}
