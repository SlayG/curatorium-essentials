package dev.curatorium.essentials.discord;

import dev.curatorium.essentials.CuratoriumEssentials;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.minecraft.server.MinecraftServer;

/**
 * The Discord server's custom emojis, kept current from Discord's emoji events.
 *
 * <p>This is a public API for other mods (Curatorium Emotes sends the list to clients). It uses
 * plain Java types only, so nothing from the bundled, relocated JDA leaks out.
 */
public final class DiscordEmojis {
    /** One custom emoji. {@code id} is Discord's snowflake; images are at cdn.discordapp.com/emojis/{id}. */
    public record Emote(String name, long id, boolean animated) {}

    /** {@code :name:} as typed in game. Discord names are 2 to 32 letters, digits and underscores. */
    public static final Pattern TOKEN = Pattern.compile(":([A-Za-z0-9_]{2,32}):");

    private static volatile List<Emote> emotes = List.of();
    private static final List<Consumer<List<Emote>>> listeners = new CopyOnWriteArrayList<>();

    /** The current emojis, sorted by name. Empty while the bot is off or emojis are turned off. */
    public static List<Emote> snapshot() {
        return emotes;
    }

    /**
     * Calls {@code listener} on the server thread with the new list whenever it changes (also
     * when the bot logs in, and with an empty list when it stops).
     */
    public static void addListener(Consumer<List<Emote>> listener) {
        listeners.add(listener);
    }

    /** The emoji with this name: an exact match first, then ignoring case. Null if none. */
    public static Emote find(String name) {
        List<Emote> list = emotes;
        for (Emote e : list) if (e.name().equals(name)) return e;
        for (Emote e : list) if (e.name().equalsIgnoreCase(name)) return e;
        return null;
    }

    /**
     * Text a player wrote, ready for Discord: markdown escaped, and each known {@code :name:}
     * turned into the emoji itself ({@code <:name:id>}, or {@code <a:name:id>} when animated).
     */
    public static String toDiscord(String plain) {
        if (emotes.isEmpty()) return Discord.escape(plain);
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(plain);
        int from = 0;
        while (m.find()) {
            Emote e = find(m.group(1));
            if (e == null) continue;
            out.append(Discord.escape(plain.substring(from, m.start())));
            out.append(e.animated() ? "<a:" : "<:").append(e.name()).append(':').append(Long.toUnsignedString(e.id())).append('>');
            from = m.end();
        }
        out.append(Discord.escape(plain.substring(from)));
        return out.toString();
    }

    /** Reads the emojis of bot.guild from JDA's cache. Called from JDA's threads. */
    static void refresh(JDA jda) {
        List<Emote> list = new ArrayList<>();
        Guild guild = guild(jda);
        if (guild != null && DiscordConfig.EMOJIS.get()) {
            for (RichCustomEmoji e : guild.getEmojis()) {
                if (e.isAvailable()) list.add(new Emote(e.getName(), e.getIdLong(), e.isAnimated()));
            }
        }
        list.sort(Comparator.comparing((Emote e) -> e.name().toLowerCase(Locale.ROOT)).thenComparingLong(Emote::id));
        set(List.copyOf(list));
    }

    static boolean isWatched(Guild g) {
        Guild ours = guild(g.getJDA());
        return ours != null && ours.getIdLong() == g.getIdLong();
    }

    private static Guild guild(JDA jda) {
        String id = DiscordConfig.GUILD.get().trim();
        if (id.isEmpty()) {
            // No guild set: only unambiguous when the bot is in exactly one.
            return jda.getGuilds().size() == 1 ? jda.getGuilds().get(0) : null;
        }
        try {
            return jda.getGuildById(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void clear() {
        set(List.of());
    }

    /** Package-private for tests. */
    static void set(List<Emote> list) {
        if (list.equals(emotes)) return;
        emotes = list;
        Discord.LOG.info("Discord: {} custom emoji(s) available in game", list.size());
        MinecraftServer server = CuratoriumEssentials.server();
        if (server == null) return;
        server.execute(() -> {
            for (Consumer<List<Emote>> l : listeners) {
                try {
                    l.accept(list);
                } catch (RuntimeException ex) {
                    Discord.LOG.warn("An emoji listener failed", ex);
                }
            }
        });
    }

    private DiscordEmojis() {}
}
