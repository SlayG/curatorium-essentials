package dev.curatorium.essentials.text;

import dev.curatorium.essentials.CuratoriumEssentials;
import dev.curatorium.essentials.player.Names;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The text files in {@code config/curatorium_essentials/}: {@code motd.txt}, {@code rules.txt},
 * {@code info.txt} and {@code custom.txt}. Defaults are copied out of the jar on first use. A
 * line starting with {@code #} begins a chapter (e.g. {@code #Shops}); text before the first
 * chapter is the file's opening text. Placeholders: {PLAYER} {DISPLAYNAME} {ONLINE}
 * {MAXPLAYERS} {WORLD}.
 */
public final class TextFiles {
    public static final int PAGE_LINES = 9;
    private static final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    public static void reload() {
        cache.clear();
    }

    /** The file's lines (without placeholders filled). */
    public static List<String> lines(String name) {
        return cache.computeIfAbsent(name, TextFiles::read);
    }

    /** Chapters by lower-case name; "" holds the text before the first chapter. Keeps file order. */
    public static Map<String, List<String>> chapters(String name) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> current = new ArrayList<>();
        out.put("", current);
        for (String line : lines(name)) {
            if (line.startsWith("#") && line.length() > 1) {
                current = new ArrayList<>();
                out.put(line.substring(1).trim().toLowerCase(Locale.ROOT), current);
            } else {
                current.add(line);
            }
        }
        return out;
    }

    /** Chapter names as written in the file. */
    public static List<String> chapterTitles(String name) {
        List<String> out = new ArrayList<>();
        for (String line : lines(name)) {
            if (line.startsWith("#") && line.length() > 1) out.add(line.substring(1).trim());
        }
        return out;
    }

    public static String fill(String line, CommandSourceStack source) {
        var server = source.getServer();
        ServerPlayer p = source.getPlayer();
        return line.replace("{PLAYER}", p == null ? "Console" : p.getGameProfile().getName())
                .replace("{DISPLAYNAME}", p == null ? "Console" : Names.rawDisplayName(p))
                .replace("{ONLINE}", String.valueOf(server.getPlayerCount()))
                .replace("{MAXPLAYERS}", String.valueOf(server.getMaxPlayers()))
                .replace("{WORLD}", p == null ? "" : p.level().dimension().location().getPath());
    }

    /**
     * Sends page {@code page} (from 1) of {@code lines}, with a footer pointing at the next page
     * as {@code nextCommand + " " + (page + 1)} when there is one.
     */
    public static void sendPage(CommandSourceStack source, List<String> lines, int page, String nextCommand) {
        int pages = Math.max(1, (lines.size() + PAGE_LINES - 1) / PAGE_LINES);
        int p = Math.min(Math.max(page, 1), pages);
        for (int i = (p - 1) * PAGE_LINES; i < Math.min(lines.size(), p * PAGE_LINES); i++) {
            Component line = Text.format(fill(lines.get(i), source));
            source.sendSystemMessage(line);
        }
        if (pages > 1) {
            source.sendSystemMessage(p < pages
                    ? Messages.get("page.next", p, pages, nextCommand + " " + (p + 1))
                    : Messages.get("page.last", p, pages));
        }
    }

    private static List<String> read(String name) {
        Path file = FMLPaths.CONFIGDIR.get().resolve("curatorium_essentials").resolve(name);
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream in = TextFiles.class.getResourceAsStream("/curatorium_essentials/" + name)) {
                    if (in == null) return List.of();
                    Files.copy(in, file);
                }
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
            return lines;
        } catch (IOException e) {
            CuratoriumEssentials.LOGGER.error("Could not read {}", file, e);
            return List.of();
        }
    }

    private TextFiles() {}
}
