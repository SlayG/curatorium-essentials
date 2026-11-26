package dev.curatorium.essentials.mixin;

import dev.curatorium.essentials.player.Vanish;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanished players aren't sent to players who can't see them (their entity simply isn't tracked). */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
    private void essentials$hideVanished(ServerPlayer viewer, CallbackInfoReturnable<Boolean> cir) {
        if (Vanish.hides((ServerPlayer) (Object) this, viewer)) cir.setReturnValue(false);
    }
}
