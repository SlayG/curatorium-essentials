package dev.curatorium.essentials.text;

import dev.curatorium.essentials.CuratoriumEssentials;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Every message the mod shows. Defaults ship inside the jar
 * ({@code curatorium_essentials/messages.properties}); on first start they are copied to
 * {@code config/curatorium_essentials/messages.properties}, where any line can be changed. Keys
 * missing from that file fall back to the default, so new messages from updates still show.
 *
 * <p>Clients don't have this mod's language file, so everything is sent as finished text.
 */
public final class Messages {
    private static final String RESOURCE = "/curatorium_essentials/messages.properties";
    private static Map<String, String> messages = Map.of();

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("curatorium_essentials").resolve("messages.properties");
    }

    public static void load() {
        Map<String, String> out = new HashMap<>();
        Properties defaults = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("missing " + RESOURCE);
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            CuratoriumEssentials.LOGGER.error("Could not read the built-in messages", e);
        }
        defaults.forEach((k, v) -> out.put((String) k, (String) v));

        Path file = file();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream in = Messages.class.getResourceAsStream(RESOURCE)) {
                    if (in != null) Files.copy(in, file);
                }
            } else {
                Properties custom = new Properties();
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    custom.load(r);
                }
                custom.forEach((k, v) -> out.put((String) k, (String) v));
            }
        } catch (IOException e) {
            CuratoriumEssentials.LOGGER.error("Could not read {}; using the built-in messages", file, e);
        }
        messages = out;
    }

    /** The raw template for {@code key}, or the key itself if it doesn't exist. */
    public static String raw(String key) {
        return messages.getOrDefault(key, key);
    }

    /** The message for {@code key} with its placeholders filled. */
    public static MutableComponent get(String key, Object... args) {
        return Text.format(raw(key), args);
    }

    private Messages() {}
}
