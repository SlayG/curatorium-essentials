package dev.curatorium.essentials.perm;

import dev.curatorium.essentials.CuratoriumEssentials;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
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
            if (player != null) {
                server.getCommands().sendCommands(player);
                player.refreshTabListName(); // prefix/suffix may have changed
            }
        }));
        return true;
    }

    static Perms.Meta meta(ServerPlayer player) {
        CachedMetaData md;
        try {
            md = api.getPlayerAdapter(ServerPlayer.class).getMetaData(player);
        } catch (IllegalStateException e) {
            User user = api.getUserManager().getUser(player.getUUID());
            if (user == null) return Perms.Meta.NONE;
            md = user.getCachedData().getMetaData(QueryOptions.nonContextual());
        }
        String prefix = md.getPrefix(), suffix = md.getSuffix();
        return new Perms.Meta(prefix == null ? "" : prefix, suffix == null ? "" : suffix, md.getPrimaryGroup());
    }

    /** True or false if LuckPerms has an answer for the node, null if it's unset. */
    static Boolean check(ServerPlayer player, String node) {
        CachedPermissionData data;
        try {
            data = api.getPlayerAdapter(ServerPlayer.class).getPermissionData(player);
        } catch (IllegalStateException e) {
            // While a player is still joining, LuckPerms hasn't attached its data to them yet
            // (vanilla checks command permissions then). Ask without world contexts instead.
            User user = api.getUserManager().getUser(player.getUUID());
            if (user == null) return null;
            data = user.getCachedData().getPermissionData(QueryOptions.nonContextual());
        }
        Tristate t = data.checkPermission(node);
        return t == Tristate.UNDEFINED ? null : t.asBoolean();
    }

    private LuckPermsHook() {}
}
