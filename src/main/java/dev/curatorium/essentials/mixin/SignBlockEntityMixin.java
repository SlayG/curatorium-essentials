package dev.curatorium.essentials.mixin;

import dev.curatorium.essentials.economy.Signs;
import java.util.List;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After a player writes a sign: turn it into a shop sign, or make sure it isn't one. */
@Mixin(SignBlockEntity.class)
abstract class SignBlockEntityMixin {
    @Inject(method = "updateSignText", at = @At("TAIL"))
    private void essentials$afterEdit(Player player, boolean front, List<FilteredText> lines, CallbackInfo ci) {
        Signs.afterEdit((SignBlockEntity) (Object) this, player, front);
    }
}
