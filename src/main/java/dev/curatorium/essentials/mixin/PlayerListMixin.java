package dev.curatorium.essentials.mixin;

import dev.curatorium.essentials.player.Vanish;
import java.util.Collection;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanish at login: the join message and the tab-list entries only go to players who may see a
 * vanished player, so nobody glimpses them for a moment before they're hidden.
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Redirect(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void essentials$joinMessage(PlayerList list, Component message, boolean overlay,
                                        Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        Vanish.broadcastJoinOrQuit(list, player, message);
    }

    /** The tab list sent to the joining player leaves out vanished players they can't see. */
    @Redirect(method = "placeNewPlayer", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket;createPlayerInitializing(Ljava/util/Collection;)Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket;"))
    private ClientboundPlayerInfoUpdatePacket essentials$initialTabList(Collection<ServerPlayer> players,
                                                                         Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        return ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players.stream().filter(p -> Vanish.canSee(player, p)).toList());
    }

    /** The joining player's own tab entry only goes to players who can see them. */
    @Redirect(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
    private void essentials$announceTabEntry(PlayerList list, Packet<?> packet,
                                             Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        for (ServerPlayer p : list.getPlayers()) {
            if (Vanish.canSee(p, player)) p.connection.send(packet);
        }
    }

    /** Discord Link's "must link to join": refused before the player is placed, so no join message shows. */
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void essentials$mustLink(java.net.SocketAddress address, com.mojang.authlib.GameProfile profile,
                                     CallbackInfoReturnable<Component> cir) {
        if (cir.getReturnValue() != null) return;
        Component kick = dev.curatorium.essentials.discord.DiscordCommands.mustLink(((PlayerList) (Object) this).getServer(), profile);
        if (kick != null) cir.setReturnValue(kick);
    }
}
