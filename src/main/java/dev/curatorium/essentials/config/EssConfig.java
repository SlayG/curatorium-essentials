package dev.curatorium.essentials.config;

import dev.curatorium.essentials.CuratoriumEssentials;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/curatorium_essentials/config.toml}. NeoForge reloads it when the file changes;
 * {@link #bake()} turns the raw values into the lookups the rest of the mod reads.
 */
public final class EssConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> OVERRIDE_VANILLA;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> COOLDOWNS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> COSTS;
    private static final ModConfigSpec.BooleanValue COUNT_ENDER_CHEST;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Which commands exist and how they behave. Use each command's main name, not an alias.")
                .push("commands");
        DISABLED = b.comment("Commands that are not registered at all, e.g. [\"nuke\", \"kittycannon\"].",
                        "Changing this needs a server restart.")
                .defineListAllowEmpty("disabled", List.of(), EssConfig::isString);
        OVERRIDE_VANILLA = b.comment("Vanilla commands that Essentials replaces with its own version, when it has one.",
                        "Remove a name to keep the vanilla command. Changing this needs a server restart.")
                .defineListAllowEmpty("overrideVanilla", List.of(
                        "msg", "tell", "w", "me", "tp", "teleport", "give", "kick", "ban", "ban-ip",
                        "pardon", "pardon-ip", "time", "weather", "gamemode", "kill", "list", "help"),
                        EssConfig::isString);
        COOLDOWNS = b.comment("Per-command cooldowns as \"command=seconds\", e.g. [\"heal=60\"].",
                        "Bypass with essentials.commandcooldowns.bypass (or essentials.commandcooldowns.bypass.<command>).")
                .defineListAllowEmpty("cooldowns", List.of(), EssConfig::isString);
        COSTS = b.comment("Per-command prices in spurs, paid with Numismatics coins from the player's inventory,",
                        "as \"command=spurs\", e.g. [\"repair=64\"]. Bypass with essentials.nocommandcost.<command>",
                        "or essentials.nocommandcost.all.")
                .defineListAllowEmpty("costs", List.of(), EssConfig::isString);
        b.pop();

        b.comment("Money is the Numismatics coins players carry. Nothing is stored as a virtual balance.")
                .push("economy");
        COUNT_ENDER_CHEST = b.comment("Also count and spend coins in the player's ender chest.")
                .define("countEnderChest", false);
        b.pop();

        b.comment("Chat (EssentialsX Chat). Formats use & colour codes and these placeholders:",
                "{DISPLAYNAME} {USERNAME} {NICKNAME} {PREFIX} {SUFFIX} {GROUP} {WORLDNAME} {MESSAGE}",
                "{PREFIX}, {SUFFIX} and {GROUP} come from LuckPerms.").push("chat");
        CHAT_FORMAT = b.comment("Format for everyone without a group format.")
                .define("format", "{PREFIX}{DISPLAYNAME}{SUFFIX}&7: &r{MESSAGE}");
        GROUP_FORMATS = b.comment("Per LuckPerms group (primary group), as \"group=format\",",
                        "e.g. [\"staff=&c[Staff] &r{DISPLAYNAME}&7: &f{MESSAGE}\"].")
                .defineListAllowEmpty("groupFormats", List.of(), EssConfig::isString);
        CHAT_RADIUS = b.comment("Local chat: only players within this many blocks (same dimension) see a message.",
                        "0 turns local chat off: everyone sees everything. Start a message with ! to shout",
                        "to everyone (essentials.chat.shout) or ? to ask everyone (essentials.chat.question).")
                .defineInRange("radius", 0, 0, 100_000);
        b.pop();

        b.comment("Nicknames and how names show in the tab list.").push("names");
        NICK_PREFIX = b.comment("Shown before every nickname, so players can tell it isn't a real name. \"\" for none.")
                .define("nickPrefix", "~");
        NICK_MAX_LENGTH = b.comment("Longest nickname allowed, not counting colour codes.")
                .defineInRange("nickMaxLength", 16, 1, 64);
        TAB_DISPLAY_NAME = b.comment("Show nicknames (and AFK) in the tab list.")
                .define("tabListDisplayName", true);
        TAB_PREFIX = b.comment("Also show the LuckPerms prefix and suffix in the tab list.")
                .define("tabListPrefix", false);
        b.pop();

        b.comment("Away from keyboard.").push("afk");
        AUTO_AFK = b.comment("Seconds without moving or chatting before a player is marked AFK (needs essentials.afk.auto). 0 = never.")
                .defineInRange("autoAfkSeconds", 300, 0, 86_400);
        AFK_KICK = b.comment("Seconds without activity before a player is kicked (unless essentials.afk.kickexempt). 0 = never.")
                .defineInRange("kickSeconds", 0, 0, 86_400);
        AFK_BROADCAST = b.comment("Tell everyone when a player goes AFK or comes back.")
                .define("broadcast", true);
        b.pop();

        b.comment("What players see when they join.").push("join");
        MOTD_ON_JOIN = b.comment("Show motd.txt on join.").define("showMotd", true);
        MAIL_ON_JOIN = b.comment("Tell players about unread mail on join.").define("notifyMail", true);
        b.pop();

        b.push("misc");
        NEAR_RADIUS = b.comment("Default /near radius.").defineInRange("nearRadius", 200, 1, 100_000);
        NEAR_MAX = b.comment("Largest /near radius (unless essentials.near.maxexempt).").defineInRange("nearMaxRadius", 1000, 1, 100_000);
        MAILS_PER_MINUTE = b.comment("Most mails one player can send per minute.").defineInRange("mailsPerMinute", 5, 1, 1000);
        b.pop();

        b.comment("Bans, kicks and mutes.").push("moderation");
        MUTE_BLOCKED = b.comment("Commands a muted player can't use (any name or alias; Essentials' own aliases are",
                        "matched to their command). /mail send is always blocked while muted.")
                .defineListAllowEmpty("muteBlockedCommands", List.of("msg", "r", "me", "helpop", "broadcast",
                        "broadcastworld", "say", "teammsg", "tm", "tell", "w"), EssConfig::isString);
        BAN_DEFAULT_REASON = b.comment("Reason used when a ban, kick or mute gives none.")
                .define("defaultReason", "The ban hammer has spoken!");
        b.pop();

        b.comment("Jails (/setjail, /togglejail).").push("jail");
        JAIL_ALLOWED = b.comment("Commands a jailed player may still use. Everything else is blocked.")
                .defineListAllowEmpty("allowedCommands", List.of("msg", "r", "mail", "helpop", "rules", "motd",
                        "list", "help", "afk", "togglejail", "ping", "playtime"), EssConfig::isString);
        JAIL_ONLINE_TIME = b.comment("Timed jail sentences only count down while the player is online.")
                .define("countOnlineTimeOnly", false);
        JAIL_RADIUS = b.comment("How far (blocks) a jailed player may move from the jail point before being pulled back.",
                        "0 holds them in place.")
                .defineInRange("radius", 0, 0, 256);
        b.pop();

        b.comment("Item and inventory commands.").push("items");
        ITEM_BLACKLIST = b.comment("Items /item and /give refuse (unless essentials.itemspawn.exempt), e.g. [\"minecraft:bedrock\"].")
                .defineListAllowEmpty("spawnBlacklist", List.of(), EssConfig::isString);
        CLEAR_CONFIRM = b.comment("/clearinventory asks you to run it again to confirm, unless the player turns this off",
                        "with /clearinventoryconfirmtoggle.")
                .define("confirmClearInventory", true);
        b.pop();

        b.comment("World commands.").push("world");
        SPAWNMOB_LIMIT = b.comment("Most mobs one /spawnmob can spawn (unless essentials.spawnmob.nolimit).")
                .defineInRange("spawnmobLimit", 10, 1, 1000);
        REMOVE_MAX_RADIUS = b.comment("Largest /remove radius; 0 for no limit (the whole dimension when no radius is given).")
                .defineInRange("removeMaxRadius", 0, 0, 100_000);
        BACKUP_COMMAND = b.comment("Shell command /backup runs, e.g. \"/home/minecraft/backup.sh\". \"\" turns /backup off.",
                        "The world is saved first and saving is paused until the command finishes.")
                .define("backupCommand", "");
        b.pop();

        b.comment("Kits (/kit, /createkit).").push("kits");
        FIRST_JOIN_KIT = b.comment("Kit given to players on their first join. \"\" for none.")
                .define("firstJoinKit", "");
        b.pop();

        SPEC = b.build();
    }

    private static final ModConfigSpec.ConfigValue<List<? extends String>> MUTE_BLOCKED;
    public static final ModConfigSpec.ConfigValue<String> BAN_DEFAULT_REASON;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> JAIL_ALLOWED;
    public static final ModConfigSpec.BooleanValue JAIL_ONLINE_TIME;
    public static final ModConfigSpec.IntValue JAIL_RADIUS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_BLACKLIST;
    public static final ModConfigSpec.BooleanValue CLEAR_CONFIRM;
    public static final ModConfigSpec.IntValue SPAWNMOB_LIMIT;
    public static final ModConfigSpec.IntValue REMOVE_MAX_RADIUS;
    public static final ModConfigSpec.ConfigValue<String> BACKUP_COMMAND;
    public static final ModConfigSpec.ConfigValue<String> FIRST_JOIN_KIT;

    private static Set<String> muteBlocked = Set.of();
    private static Set<String> jailAllowed = Set.of();
    private static Set<String> itemBlacklist = Set.of();

    /** Whether a muted player is blocked from {@code command} (a main command name or a vanilla label). */
    public static boolean muteBlocks(String command) {
        return muteBlocked.contains(command);
    }

    public static boolean jailAllows(String command) {
        return jailAllowed.contains(command);
    }

    public static boolean itemBlacklisted(String itemId) {
        return itemBlacklist.contains(itemId);
    }

    public static final ModConfigSpec.ConfigValue<String> CHAT_FORMAT;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> GROUP_FORMATS;
    public static final ModConfigSpec.IntValue CHAT_RADIUS;
    public static final ModConfigSpec.ConfigValue<String> NICK_PREFIX;
    public static final ModConfigSpec.IntValue NICK_MAX_LENGTH;
    public static final ModConfigSpec.BooleanValue TAB_DISPLAY_NAME;
    public static final ModConfigSpec.BooleanValue TAB_PREFIX;
    public static final ModConfigSpec.IntValue AUTO_AFK;
    public static final ModConfigSpec.IntValue AFK_KICK;
    public static final ModConfigSpec.BooleanValue AFK_BROADCAST;
    public static final ModConfigSpec.BooleanValue MOTD_ON_JOIN;
    public static final ModConfigSpec.BooleanValue MAIL_ON_JOIN;
    public static final ModConfigSpec.IntValue NEAR_RADIUS;
    public static final ModConfigSpec.IntValue NEAR_MAX;
    public static final ModConfigSpec.IntValue MAILS_PER_MINUTE;

    private static Map<String, String> groupFormats = Map.of();

    /** The chat format for a LuckPerms group, or the default format. */
    public static String chatFormat(String group) {
        if (group != null) {
            String f = groupFormats.get(group.toLowerCase(Locale.ROOT));
            if (f != null) return f;
        }
        return CHAT_FORMAT.get();
    }

    private static Set<String> disabled = Set.of();
    private static Set<String> overrideVanilla = Set.of();
    private static Map<String, Long> cooldowns = Map.of();
    private static Map<String, Long> costs = Map.of();
    private static boolean countEnderChest;

    private static boolean isString(Object o) {
        return o instanceof String;
    }

    public static void bake() {
        if (!SPEC.isLoaded()) return;
        disabled = lower(DISABLED.get());
        overrideVanilla = lower(OVERRIDE_VANILLA.get());
        cooldowns = pairs(COOLDOWNS.get(), "cooldowns");
        costs = pairs(COSTS.get(), "costs");
        countEnderChest = COUNT_ENDER_CHEST.get();
        muteBlocked = lower(MUTE_BLOCKED.get());
        jailAllowed = lower(JAIL_ALLOWED.get());
        itemBlacklist = lower(ITEM_BLACKLIST.get());
        Map<String, String> formats = new HashMap<>();
        for (String entry : GROUP_FORMATS.get()) {
            int eq = entry.indexOf('=');
            if (eq < 1) {
                CuratoriumEssentials.LOGGER.warn("Ignoring bad chat.groupFormats entry \"{}\" (expected group=format)", entry);
                continue;
            }
            formats.put(entry.substring(0, eq).trim().toLowerCase(Locale.ROOT), entry.substring(eq + 1));
        }
        groupFormats = formats;
    }

    private static Set<String> lower(List<? extends String> list) {
        Set<String> out = new HashSet<>();
        for (String s : list) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static Map<String, Long> pairs(List<? extends String> list, String what) {
        Map<String, Long> out = new HashMap<>();
        for (String entry : list) {
            int eq = entry.indexOf('=');
            try {
                if (eq < 1) throw new NumberFormatException();
                long value = Long.parseLong(entry.substring(eq + 1).trim());
                if (value < 0) throw new NumberFormatException();
                out.put(entry.substring(0, eq).trim().toLowerCase(Locale.ROOT), value);
            } catch (NumberFormatException e) {
                CuratoriumEssentials.LOGGER.warn("Ignoring bad commands.{} entry \"{}\" (expected command=number)", what, entry);
            }
        }
        return out;
    }

    public static boolean isDisabled(String command) {
        return disabled.contains(command);
    }

    public static boolean overridesVanilla(String label) {
        return overrideVanilla.contains(label);
    }

    /** Cooldown in seconds, 0 if none. */
    public static long cooldown(String command) {
        return cooldowns.getOrDefault(command, 0L);
    }

    /** Price in spurs, 0 if free. */
    public static long cost(String command) {
        return costs.getOrDefault(command, 0L);
    }

    public static boolean countEnderChest() {
        return countEnderChest;
    }

    private EssConfig() {}
}
