package dev.curatorium.essentials.data;

import com.google.gson.JsonParseException;
import dev.curatorium.essentials.CuratoriumEssentials;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/** One JSON file in the mod's world folder (jails.json, kits.json…), read with Gson. */
public final class JsonFile {
    public static Path path(String name) {
        return UserStore.root().resolve(name);
    }

    /** Reads the file, or returns {@code empty} if it's missing or unreadable (an unreadable file is kept as .broken). */
    public static <T> T read(String name, Type type, Supplier<T> empty) {
        Path file = path(name);
        if (Files.notExists(file)) return empty.get();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T value = UserStore.GSON.fromJson(r, type);
            return value == null ? empty.get() : value;
        } catch (IOException | JsonParseException e) {
            CuratoriumEssentials.LOGGER.error("Could not read {}; starting it empty", file, e);
            try {
                Files.move(file, file.resolveSibling(name + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // left as it is
            }
            return empty.get();
        }
    }

    /** Writes the file now (these files are small and rarely change). */
    public static void write(String name, Object value) {
        UserStore.write(path(name), UserStore.GSON.toJson(value));
    }

    private JsonFile() {}
}
