package dev.curatorium.essentials.mixin;

import dev.curatorium.essentials.player.Vanish;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A vanished player's quit message only goes to players who can see vanished players. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Redirect(method = "removePlayerFromWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void essentials$quitMessage(PlayerList list, Component message, boolean overlay) {
        Vanish.broadcastJoinOrQuit(list, ((ServerGamePacketListenerImpl) (Object) this).player, message);
    }
}
