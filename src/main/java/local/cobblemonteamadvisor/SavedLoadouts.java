package local.cobblemonteamadvisor;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SavedLoadouts {
    record Entry(String name, List<String> pokemon) { }

    static List<Entry> read(Path path) throws IOException {
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            Entry[] saved = new Gson().fromJson(Files.readString(path), Entry[].class);
            if (saved == null || saved.length > 100) throw new IllegalArgumentException("Invalid loadouts");
            for (Entry loadout : saved) {
                if (loadout == null || loadout.name == null || loadout.name.length() > 40 || loadout.pokemon == null
                        || loadout.pokemon.isEmpty() || loadout.pokemon.size() > 6 || Set.copyOf(loadout.pokemon).size() != loadout.pokemon.size())
                    throw new IllegalArgumentException("Invalid loadout");
                for (String id : loadout.pokemon) java.util.UUID.fromString(id);
            }
            return new ArrayList<>(List.of(saved));
        } catch (RuntimeException error) { throw new IOException("Cannot read saved loadouts", error); }
    }

    static void save(Path path, List<Entry> loadouts) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "loadouts", ".tmp");
        try {
            Files.writeString(temporary, new Gson().toJson(loadouts));
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
