package dev.curatorium.essentials.perm;

import dev.curatorium.essentials.CuratoriumEssentials;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything that touches the LuckPerms API. Only loaded when LuckPerms is installed, so the
 * mod still runs without it.
 */
final class LuckPermsHook {
    private static LuckPerms api;

    static boolean init(MinecraftServer server) {
        try {
            api = LuckPermsProvider.get();
        } catch (IllegalStateException | NoClassDefFoundError e) {
            CuratoriumEssentials.LOGGER.warn("LuckPerms is installed but its API isn't available", e);
            return false;
        }
        // When a player's permissions change, resend their command list so the commands they
        // gained or lost appear or disappear in tab completion straight away.
        api.getEventBus().subscribe(UserDataRecalculateEvent.class, event -> server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(event.getUser().getUniqueId());
            if (player != null) server.getCommands().sendCommands(player);
        }));
        return true;
    }

    /** True or false if LuckPerms has an answer for the node, null if it's unset. */
    static Boolean check(ServerPlayer player, String node) {
        Tristate t = api.getPlayerAdapter(ServerPlayer.class).getPermissionData(player).checkPermission(node);
        return t == Tristate.UNDEFINED ? null : t.asBoolean();
    }

    private LuckPermsHook() {}
}
