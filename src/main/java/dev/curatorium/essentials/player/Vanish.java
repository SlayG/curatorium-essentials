package dev.curatorium.essentials.player;

import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.mixin.ChunkMapAccessor;
import dev.curatorium.essentials.mixin.TrackedEntityAccessor;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * /vanish: a vanished player isn't sent to players who can't see them (no entity, no tab-list
 * entry, no join or quit message) and mobs don't target them. Players with
 * {@code essentials.vanish.see} (default op) still see them. Vanish lasts across relogs.
 *
 * <p>Sounds and particles the player causes (footsteps, block breaking) still reach others;
 * hiding those needs more invasive changes than a server-side mod should make.
 */
public final class Vanish {
    public static final String SEE = "essentials.vanish.see";

    public static void registerEvents(IEventBus bus) {
        // The tab list at login is filtered by PlayerListMixin.
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer joined && isVanished(joined)) {
                joined.sendSystemMessage(Messages.get("vanish.stillVanished"));
            }
        });
        bus.addListener(LivingChangeTargetEvent.class, e -> {
            if (e.getNewAboutToBeSetTarget() instanceof ServerPlayer p && isVanished(p)) e.setCanceled(true);
        });
    }

    public static boolean isVanished(ServerPlayer p) {
        return UserStore.get(p).vanished;
    }

    /** Whether {@code viewer} sees {@code target} (always, unless the target is vanished). */
    public static boolean canSee(ServerPlayer viewer, ServerPlayer target) {
        return viewer == target || !isVanished(target) || Perms.has(viewer, SEE, Perms.OP);
    }

    public static boolean canSee(CommandSourceStack source, ServerPlayer target) {
        ServerPlayer viewer = source.getPlayer();
        if (viewer != null) return canSee(viewer, target);
        return true; // console, command blocks
    }

    /** For the entity-tracking mixin: true when {@code target} must not be sent to {@code viewer}. */
    public static boolean hides(ServerPlayer target, ServerPlayer viewer) {
        return target != viewer && UserStore.get(target).vanished && !Perms.has(viewer, SEE, Perms.OP);
    }

    public static void set(ServerPlayer p, boolean vanished) {
        var d = UserStore.get(p);
        if (d.vanished == vanished) return;
        d.vanished = vanished;
        d.markDirty();
        PlayerList list = p.server.getPlayerList();
        for (ServerPlayer other : list.getPlayers()) {
            if (other == p || Perms.has(other, SEE, Perms.OP)) continue;
            other.connection.send(vanished ? remove(p) : ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(p)));
        }
        // Make the entity tracker re-check who may see the player, now rather than when they next move.
        Object tracked = ((ChunkMapAccessor) p.serverLevel().getChunkSource().chunkMap).essentials$entityMap().get(p.getId());
        if (tracked instanceof TrackedEntityAccessor t) {
            for (ServerPlayer other : p.serverLevel().players()) {
                if (other != p) t.essentials$updatePlayer(other);
            }
        }
        if (vanished) {
            // Mobs already chasing the player lose them.
            for (var mob : p.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(64))) {
                if (mob.getTarget() == p) mob.setTarget(null);
            }
        }
        Names.refresh(p);
        dev.curatorium.essentials.discord.DiscordCommands.vanished(p, vanished);
    }

    private static ClientboundPlayerInfoRemovePacket remove(ServerPlayer p) {
        return new ClientboundPlayerInfoRemovePacket(List.of(p.getUUID()));
    }

    /** Join/quit messages (from the mixins): everyone for a visible player, only seers for a vanished one. */
    public static void broadcastJoinOrQuit(PlayerList list, ServerPlayer player, Component message) {
        if (!isVanished(player)) {
            list.broadcastSystemMessage(message, false);
            return;
        }
        Component tagged = Messages.get("vanish.tag").append(message);
        for (ServerPlayer p : list.getPlayers()) {
            if (p != player && Perms.has(p, SEE, Perms.OP)) p.sendSystemMessage(tagged);
        }
        list.getServer().sendSystemMessage(tagged);
    }

    private Vanish() {}
}
