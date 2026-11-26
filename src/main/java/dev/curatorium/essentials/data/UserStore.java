package dev.curatorium.essentials.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.curatorium.essentials.CuratoriumEssentials;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Loads and saves {@link UserData}. Online players are kept in memory; offline players are read
 * from disk when asked for and dropped again once saved. Files are written on a background
 * thread (serialised on the server thread first, so nothing changes mid-write) and flushed when
 * the server stops.
 */
public final class UserStore {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int SAVE_EVERY_TICKS = 20 * 30;

    private static final Map<UUID, UserData> cache = new ConcurrentHashMap<>();
    private static Path root;
    private static Path userDir;
    private static ExecutorService writer;
    private static int ticks;

    /** {@code <world>/curatorium_essentials}: the folder every data file of the mod lives in. */
    public static Path root() {
        return root;
    }

    public static void open(MinecraftServer server) {
        root = server.getWorldPath(LevelResource.ROOT).resolve("curatorium_essentials").normalize();
        userDir = root.resolve("userdata");
        try {
            Files.createDirectories(userDir);
        } catch (IOException e) {
            CuratoriumEssentials.LOGGER.error("Could not create {}", userDir, e);
        }
        cache.clear();
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Curatorium Essentials saver");
            t.setDaemon(true);
            return t;
        });
    }

    /** Saves everything and waits for the writes to finish. */
    public static void close() {
        if (writer == null) return;
        saveDirty();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                CuratoriumEssentials.LOGGER.warn("Player data took over 30 s to save; some changes may be lost");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writer = null;
        cache.clear();
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) onLogin(p);
        });
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) onLogout(p);
        });
        bus.addListener(ServerTickEvent.Post.class, e -> {
            if (++ticks >= SAVE_EVERY_TICKS) {
                ticks = 0;
                saveDirty();
            }
        });
    }

    /** The player's data, loading it (or creating it) if needed. */
    public static UserData get(ServerPlayer player) {
        return get(player.getUUID());
    }

    /** The data for any player, online or not. Creates an empty record if they've never joined. */
    public static UserData get(UUID id) {
        return cache.computeIfAbsent(id, UserStore::load);
    }

    /** Whether this player has ever joined while the mod was installed. */
    public static boolean exists(UUID id) {
        return cache.containsKey(id) || (userDir != null && Files.exists(file(id)));
    }

    private static void onLogin(ServerPlayer player) {
        UserData data = get(player);
        long now = System.currentTimeMillis();
        if (data.firstJoin == 0) data.firstJoin = now;
        data.lastName = player.getGameProfile().getName();
        data.markDirty();
    }

    private static void onLogout(ServerPlayer player) {
        UserData data = get(player);
        data.lastSeen = System.currentTimeMillis();
        data.logoutLocation = Location.of(player);
        data.markDirty();
        save(data);
        cache.remove(player.getUUID());
    }

    /** Queues every changed record for writing, and drops offline players from memory. */
    public static void saveDirty() {
        MinecraftServer server = CuratoriumEssentials.server();
        for (UserData data : cache.values()) {
            if (data.dirty) save(data);
            if (server != null && server.getPlayerList().getPlayer(data.uuid) == null) cache.remove(data.uuid);
        }
    }

    private static void save(UserData data) {
        if (writer == null) return;
        data.dirty = false;
        String json = GSON.toJson(data);
        Path target = file(data.uuid);
        writer.execute(() -> write(target, json));
    }

    /** Writes via a temp file, so a crash mid-write never leaves a half-written file. */
    public static void write(Path target, String json) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            CuratoriumEssentials.LOGGER.error("Could not save {}", target, e);
        }
    }

    private static UserData load(UUID id) {
        UserData data = null;
        if (userDir != null) {
            Path file = file(id);
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    data = GSON.fromJson(r, UserData.class);
                } catch (IOException | JsonParseException e) {
                    CuratoriumEssentials.LOGGER.error("Could not read {}; starting that player fresh", file, e);
                    backUp(file);
                }
            }
        }
        if (data == null) data = new UserData();
        data.fixNulls(id);
        return data;
    }

    /** Keeps an unreadable file as {@code .broken} instead of overwriting it. */
    private static void backUp(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CuratoriumEssentials.LOGGER.error("Could not move aside {}", file, e);
        }
    }

    private static Path file(UUID id) {
        return userDir.resolve(id + ".json");
    }

    private UserStore() {}
}
