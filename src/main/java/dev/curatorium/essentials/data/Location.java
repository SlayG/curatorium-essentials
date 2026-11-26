package dev.curatorium.essentials.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** A saved position: dimension id, coordinates and facing. Used for homes, warps, spawns and /back. */
public record Location(String dimension, double x, double y, double z, float yaw, float pitch) {
    public static Location of(Entity entity) {
        return new Location(entity.level().dimension().location().toString(),
                entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
    }

    /** Moves the player here. False if the dimension no longer exists. */
    public boolean teleport(ServerPlayer player) {
        ServerLevel level = level(player.server);
        if (level == null) return false;
        player.stopRiding();
        player.teleportTo(level, x, y, z, yaw, pitch);
        return true;
    }

    public double distanceSqr(Entity entity) {
        double dx = entity.getX() - x, dy = entity.getY() - y, dz = entity.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean sameDimension(Entity entity) {
        return entity.level().dimension().location().toString().equals(dimension);
    }

    /** The level this location is in, or null if that dimension doesn't exist (any more). */
    public ServerLevel level(MinecraftServer server) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
