package dev.curatorium.essentials.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code ChunkMap.TrackedEntity} is package-private; this lets /vanish call its updatePlayer. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
    @Invoker("updatePlayer")
    void essentials$updatePlayer(ServerPlayer player);
}
