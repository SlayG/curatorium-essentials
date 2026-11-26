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

    private Perms() {}
}
