package dev.curatorium.essentials.perm;

import dev.curatorium.essentials.CuratoriumEssentials;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Permission checks. Node names follow EssentialsX ({@code essentials.<command>},
 * {@code essentials.<command>.others}, …) so its documentation applies.
 *
 * <p>With LuckPerms installed, a node LuckPerms has an answer for (set true or false on the
 * player or one of their groups, wildcards included) decides. Otherwise the node's default
 * applies: {@link #ALL} lets everyone use it, a number is the vanilla op level needed (2 for
 * the moderator/admin commands, 4 for server management). Console and command blocks use the
 * default only.
 */
public final class Perms {
    /** Default for nodes every player has. */
    public static final int ALL = 0;
    /** Default for staff nodes: op level 2. */
    public static final int OP = 2;
    /** Default for server-management nodes: op level 4. */
    public static final int ADMIN = 4;

    /** Default for nodes nobody has unless LuckPerms grants them (e.g. group.<name> checks). */
    public static final int NOBODY = 5;

    private static boolean luckPerms;

    public static void onServerStarted(MinecraftServer server) {
        luckPerms = ModList.get().isLoaded("luckperms") && LuckPermsHook.init(server);
        CuratoriumEssentials.LOGGER.info(luckPerms
                ? "Using LuckPerms for permissions"
                : "LuckPerms not found; permissions fall back to op levels");
    }

    public static boolean luckPermsActive() {
        return luckPerms;
    }

    public static boolean has(CommandSourceStack source, String node, int defaultLevel) {
        ServerPlayer player = source.getPlayer();
        if (player != null) return has(player, node, defaultLevel);
        return source.hasPermission(defaultLevel);
    }

    public static boolean has(ServerPlayer player, String node, int defaultLevel) {
        if (luckPerms) {
            Boolean answer = LuckPermsHook.check(player, node);
            if (answer != null) return answer;
        }
        return defaultLevel <= ALL || player.hasPermissions(defaultLevel);
    }

    /** A player's LuckPerms prefix, suffix and primary group ("" / null without LuckPerms). */
    public record Meta(String prefix, String suffix, String group) {
        public static final Meta NONE = new Meta("", "", null);
    }

    public static Meta meta(ServerPlayer player) {
        return luckPerms ? LuckPermsHook.meta(player) : Meta.NONE;
    }

    /** The Essentials permissions for writing formatted text, under {@code base} (e.g. "essentials.chat"). */
    public record TextPerms(boolean color, boolean format, boolean magic, boolean links) {}

    public static TextPerms textPerms(ServerPlayer player, String base) {
        return new TextPerms(has(player, base + ".color", OP), has(player, base + ".format", OP),
                has(player, base + ".magic", OP), has(player, base + ".url", ALL));
    }

    private Perms() {}
}
