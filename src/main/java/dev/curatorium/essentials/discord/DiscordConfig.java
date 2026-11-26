package dev.curatorium.essentials.discord;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/curatorium_essentials/discord.toml}. The token lives only in this file (or the
 * {@code CURATORIUM_DISCORD_TOKEN} environment variable), never in the repo or the logs.
 */
public final class DiscordConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.ConfigValue<String> TOKEN;
    public static final ModConfigSpec.ConfigValue<String> GUILD;
    public static final ModConfigSpec.ConfigValue<String> CHAT_CHANNEL;
    public static final ModConfigSpec.ConfigValue<String> STAFF_CHANNEL;
    public static final ModConfigSpec.ConfigValue<String> CONSOLE_CHANNEL;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> MESSAGE_TYPES;
    public static final ModConfigSpec.BooleanValue WEBHOOK;
    public static final ModConfigSpec.ConfigValue<String> AVATAR_URL;
    public static final ModConfigSpec.BooleanValue FROM_DISCORD;
    public static final ModConfigSpec.IntValue MAX_LENGTH;
    public static final ModConfigSpec.BooleanValue VANISH_FAKE;
    public static final ModConfigSpec.ConfigValue<String> CONSOLE_LEVEL;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> COMMAND_ROLES;
    public static final ModConfigSpec.ConfigValue<String> STATUS;
    public static final ModConfigSpec.ConfigValue<String> INVITE;
    public static final ModConfigSpec.BooleanValue LINK_REQUIRED;
    public static final ModConfigSpec.IntValue CODE_MINUTES;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ROLES_TO_GROUPS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> GROUPS_TO_ROLES;
    public static final ModConfigSpec.BooleanValue MEMBER_INTENT;
    public static final ModConfigSpec.IntValue RESYNC_MINUTES;
    public static final ModConfigSpec.BooleanValue SYNC_NICKNAMES;
    public static final ModConfigSpec.BooleanValue REMOVE_ON_UNLINK;

    /** Every kind of message the mod sends to Discord, with the channel it goes to by default. */
    static final List<String> DEFAULT_TYPES = List.of(
            "chat=chat", "join=chat", "leave=chat", "death=chat", "advancement=chat", "start=chat", "stop=chat",
            "broadcast=chat", "kick=staff", "ban=staff", "mute=staff", "jail=staff", "helpop=staff");

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("The Discord bot. Create it at https://discord.com/developers/applications, turn on the",
                "Message Content Intent (Bot page), and invite it with the bot and applications.commands scopes",
                "and the View Channels, Send Messages, Read Message History, Embed Links and Manage Webhooks",
                "permissions.").push("bot");
        ENABLED = b.comment("Connect to Discord. Changing this or the token restarts the bot.").define("enabled", false);
        TOKEN = b.comment("The bot token (Bot page, Reset Token). Keep it secret: anyone with it controls the bot.",
                "The environment variable CURATORIUM_DISCORD_TOKEN is used instead when set.").define("token", "");
        GUILD = b.comment("Your Discord server's ID (right-click the server with Developer Mode on, Copy Server ID).",
                "Slash commands are registered there, where they show at once.").define("guild", "");
        b.pop();

        b.comment("Channel IDs (right-click a channel, Copy Channel ID). Empty turns that channel off.").push("channels");
        CHAT_CHANNEL = b.comment("Two-way chat relay.").define("chat", "");
        STAFF_CHANNEL = b.comment("Staff messages: /helpop goes here, and what staff write here reaches staff in game.").define("staff", "");
        CONSOLE_CHANNEL = b.comment("The server log, and commands typed by members with a command role (below).",
                "Make it visible to staff only: the log includes players' IP addresses.").define("console", "");
        MESSAGE_TYPES = b.comment("Where each kind of message goes: type=chat, type=staff, type=console, type=<channel id>",
                "or type=none. Types: " + String.join(", ", DEFAULT_TYPES.stream().map(s -> s.substring(0, s.indexOf('='))).toList()) + ".")
                .defineListAllowEmpty("messageTypes", DEFAULT_TYPES, () -> "", o -> o instanceof String s && s.contains("="));
        b.pop();

        b.push("relay");
        WEBHOOK = b.comment("Post players' chat through a webhook, with their name and head as the avatar. Needs Manage Webhooks;",
                "without it the bot posts \"**name**: message\" itself.").define("webhook", true);
        AVATAR_URL = b.comment("Avatar for webhook messages. {name} and {uuid} are filled in.")
                .define("avatarUrl", "https://mc-heads.net/avatar/{name}/100");
        FROM_DISCORD = b.comment("Show messages from the Discord chat channel in game.").define("fromDiscord", true);
        MAX_LENGTH = b.comment("Longer Discord messages are cut to this many characters in game.").defineInRange("maxLength", 256, 16, 2000);
        VANISH_FAKE = b.comment("When someone vanishes or unvanishes, post a leave or join message for them.").define("vanishFakeMessages", true);
        b.pop();

        b.push("console");
        CONSOLE_LEVEL = b.comment("Lowest log level streamed to the console channel: ERROR, WARN, INFO or DEBUG.").define("logLevel", "INFO");
        COMMAND_ROLES = b.comment("Role IDs allowed to run server commands, from the console channel or with /execute.",
                "Empty means nobody can.").defineListAllowEmpty("commandRoles", List.of(), () -> "", o -> o instanceof String);
        b.pop();

        b.push("misc");
        STATUS = b.comment("The bot's status. {online} and {max} are filled in. Empty for none.").define("status", "{online} players online");
        INVITE = b.comment("Invite link shown by /discord in game.").define("inviteUrl", "");
        b.pop();

        b.comment("Linking Minecraft and Discord accounts: /link in game gives a code, which the player",
                "enters with /link in Discord or sends to the bot in a direct message.").push("link");
        LINK_REQUIRED = b.comment("Players must link before they can join; the kick message gives them their code.",
                "Players with essentials.link.bypass (op 4 by default) join anyway. While the bot is offline, nobody is kept out.")
                .define("required", false);
        CODE_MINUTES = b.comment("How long a link code works.").defineInRange("codeMinutes", 10, 1, 1440);
        ROLES_TO_GROUPS = b.comment("Discord roles that put linked players in a LuckPerms group, as \"<role id>=<group>\".",
                "Losing the role takes them out of the group again.")
                .defineListAllowEmpty("rolesToGroups", List.of(), () -> "", o -> o instanceof String s && s.contains("="));
        GROUPS_TO_ROLES = b.comment("LuckPerms groups that give linked players a Discord role, as \"<group>=<role id>\".",
                "Leaving the group takes the role away again. The bot needs Manage Roles, and its role must be above these.",
                "If a pair is in both lists, Discord wins when a player joins.")
                .defineListAllowEmpty("groupsToRoles", List.of(), () -> "", o -> o instanceof String s && s.contains("="));
        MEMBER_INTENT = b.comment("Sync the moment someone's Discord roles change. Needs the Server Members Intent turned on",
                "(Bot page in the developer portal); without it, roles are checked on link, on join and every resyncMinutes.")
                .define("memberIntent", false);
        RESYNC_MINUTES = b.comment("How often online linked players' Discord roles are checked again. 0 turns it off.")
                .defineInRange("resyncMinutes", 10, 0, 1440);
        SYNC_NICKNAMES = b.comment("Set linked players' Discord nickname to their Minecraft name. Needs Manage Nicknames;",
                "the server owner and members above the bot can't be renamed.").define("syncNicknames", false);
        REMOVE_ON_UNLINK = b.comment("Unlinking takes away the synced groups and roles.").define("removeOnUnlink", true);
        b.pop();
        SPEC = b.build();
    }

    /** role id -> group */
    static Map<Long, String> rolesToGroups() {
        Map<Long, String> out = new HashMap<>();
        for (String s : ROLES_TO_GROUPS.get()) {
            int i = s.indexOf('=');
            long id = parseId(s.substring(0, i));
            if (id != 0) out.put(id, s.substring(i + 1).trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** group -> role id */
    static Map<String, Long> groupsToRoles() {
        Map<String, Long> out = new HashMap<>();
        for (String s : GROUPS_TO_ROLES.get()) {
            int i = s.indexOf('=');
            long id = parseId(s.substring(i + 1));
            if (id != 0) out.put(s.substring(0, i).trim().toLowerCase(Locale.ROOT), id);
        }
        return out;
    }

    static long parseId(String s) {
        try {
            return Long.parseUnsignedLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String token() {
        String env = System.getenv("CURATORIUM_DISCORD_TOKEN");
        return env != null && !env.isBlank() ? env.trim() : TOKEN.get().trim();
    }

    /** type -> channel setting ("chat", "staff", "console", an id, or "none"). */
    static Map<String, String> types() {
        Map<String, String> out = new HashMap<>();
        for (String s : DEFAULT_TYPES) out.put(s.substring(0, s.indexOf('=')), s.substring(s.indexOf('=') + 1));
        for (String s : MESSAGE_TYPES.get()) {
            int i = s.indexOf('=');
            out.put(s.substring(0, i).trim().toLowerCase(Locale.ROOT), s.substring(i + 1).trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** A channel setting ("chat", "staff", "console" or an id) as a channel id, or 0 for none. */
    static long channel(String setting) {
        String id = switch (setting) {
            case "chat" -> CHAT_CHANNEL.get();
            case "staff" -> STAFF_CHANNEL.get();
            case "console" -> CONSOLE_CHANNEL.get();
            case "none", "" -> "";
            default -> setting;
        };
        try {
            return id.isBlank() ? 0 : Long.parseUnsignedLong(id.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private DiscordConfig() {}
}
