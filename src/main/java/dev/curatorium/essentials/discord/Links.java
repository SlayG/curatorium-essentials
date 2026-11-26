package dev.curatorium.essentials.discord;

import com.google.gson.reflect.TypeToken;
import dev.curatorium.essentials.data.JsonFile;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Linked accounts, one Minecraft account to one Discord account, kept in
 * {@code <world>/curatorium_essentials/discord-links.json}; and the short codes that link them.
 * Used from the server thread and JDA's threads, so every method is synchronized.
 */
public final class Links {
    private static final String FILE = "discord-links.json";
    /** No 0/O, 1/I/L: codes are typed by hand. */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One link. {@code discord} is the user id; the names are as they were when linked, for messages. */
    public static final class Link {
        public String discord;
        public String discordName;
        public String name;
        public long linkedAt;

        public long discordId() {
            return DiscordConfig.parseId(discord);
        }
    }

    private record Code(UUID player, String name, long expires) {}

    private static Map<UUID, Link> byPlayer = new TreeMap<>();
    private static final Map<Long, UUID> byDiscord = new HashMap<>();
    private static final Map<String, Code> codes = new HashMap<>();

    static synchronized void load() {
        byPlayer = JsonFile.read(FILE, new TypeToken<TreeMap<UUID, Link>>() {}.getType(), TreeMap::new);
        byDiscord.clear();
        byPlayer.forEach((uuid, link) -> byDiscord.put(link.discordId(), uuid));
        codes.clear();
    }

    private static void save() {
        JsonFile.write(FILE, byPlayer);
    }

    public static synchronized Link get(UUID player) {
        return byPlayer.get(player);
    }

    public static synchronized UUID player(long discordId) {
        return byDiscord.get(discordId);
    }

    public static synchronized Map<UUID, Link> all() {
        return new HashMap<>(byPlayer);
    }

    /** The player's code, the same one again while it's still valid. */
    public static synchronized String codeFor(UUID player, String name) {
        long now = System.currentTimeMillis();
        codes.values().removeIf(c -> c.expires() < now);
        for (Map.Entry<String, Code> e : codes.entrySet()) {
            if (e.getValue().player().equals(player)) return e.getKey();
        }
        String code;
        do {
            StringBuilder s = new StringBuilder(6);
            for (int i = 0; i < 6; i++) s.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            code = s.toString();
        } while (codes.containsKey(code));
        codes.put(code, new Code(player, name, now + DiscordConfig.CODE_MINUTES.get() * 60_000L));
        return code;
    }

    /** What redeeming a code did. {@code link} is set when it worked. */
    record Redeemed(String error, UUID player, Link link) {}

    /** Links the Discord user to the code's player. */
    static synchronized Redeemed redeem(String typed, long discordId, String discordName) {
        String code = typed.trim().toUpperCase(java.util.Locale.ROOT);
        Code c = codes.get(code);
        if (c == null || c.expires() < System.currentTimeMillis()) {
            codes.remove(code);
            return new Redeemed("discord.link.badCode", null, null);
        }
        UUID already = byDiscord.get(discordId);
        if (already != null) return new Redeemed("discord.link.discordTaken", already, byPlayer.get(already));
        if (byPlayer.containsKey(c.player())) {
            codes.remove(code);
            return new Redeemed("discord.link.playerTaken", c.player(), byPlayer.get(c.player()));
        }
        codes.remove(code);
        Link link = new Link();
        link.discord = Long.toUnsignedString(discordId);
        link.discordName = discordName;
        link.name = c.name();
        link.linkedAt = System.currentTimeMillis();
        byPlayer.put(c.player(), link);
        byDiscord.put(discordId, c.player());
        save();
        return new Redeemed(null, c.player(), link);
    }

    /** Removes the link; returns it, or null if there was none. */
    public static synchronized Link unlink(UUID player) {
        Link link = byPlayer.remove(player);
        if (link == null) return null;
        byDiscord.remove(link.discordId());
        save();
        return link;
    }

    /** Keeps the stored Minecraft name current (shown in Discord replies). */
    static synchronized void rename(UUID player, String name) {
        Link link = byPlayer.get(player);
        if (link != null && !name.equals(link.name)) {
            link.name = name;
            save();
        }
    }

    static synchronized void expireCodes() {
        long now = System.currentTimeMillis();
        for (Iterator<Code> it = codes.values().iterator(); it.hasNext(); ) {
            if (it.next().expires() < now) it.remove();
        }
    }

    private Links() {}
}
