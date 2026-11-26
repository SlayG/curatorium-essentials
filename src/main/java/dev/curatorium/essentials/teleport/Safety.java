package dev.curatorium.essentials.teleport;

import dev.curatorium.essentials.data.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Finding a spot a player can stand on without suffocating, burning or falling. */
public final class Safety {
    private static final int RADIUS = 3;

    /** Whether a player's feet can be at {@code feet}: room for body and head, and solid, harmless ground. */
    public static boolean safe(ServerLevel level, BlockPos feet) {
        if (feet.getY() <= level.getMinBuildHeight() || feet.getY() >= level.getMaxBuildHeight() - 1) return false;
        if (!level.getWorldBorder().isWithinBounds(feet)) return false;
        return passable(level, feet) && passable(level, feet.above()) && ground(level, feet.below());
    }

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!s.getCollisionShape(level, pos).isEmpty()) return false;
        if (s.getFluidState().is(FluidTags.LAVA)) return false;
        return !hurts(s);
    }

    private static boolean ground(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (hurts(s) || s.is(Blocks.MAGMA_BLOCK) || s.getFluidState().is(FluidTags.LAVA)) return false;
        if (s.getFluidState().is(FluidTags.WATER)) return true; // standing in water is fine
        return s.isFaceSturdy(level, pos, Direction.UP) || !s.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean hurts(BlockState s) {
        return s.is(BlockTags.FIRE) || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE)
                || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.LAVA);
    }

    /**
     * {@code dest} if it's safe, otherwise the nearest safe spot: first straight up and down from
     * it, then in widening squares up to 3 blocks out. Null if there's none.
     */
    public static Location find(ServerLevel level, Location dest) {
        BlockPos start = BlockPos.containing(dest.x(), dest.y(), dest.z());
        if (safe(level, start)) return dest;
        for (int r = 0; r <= RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos found = column(level, start.offset(dx, 0, dz));
                    if (found != null) {
                        return new Location(dest.dimension(), found.getX() + 0.5, found.getY(), found.getZ() + 0.5, dest.yaw(), dest.pitch());
                    }
                }
            }
        }
        return null;
    }

    /** The nearest safe height in this column, searching up and down from {@code pos} alternately. */
    private static BlockPos column(ServerLevel level, BlockPos pos) {
        int min = level.getMinBuildHeight() + 1, max = level.getMaxBuildHeight() - 2;
        for (int d = 0; d <= max - min; d++) {
            int up = pos.getY() + d, down = pos.getY() - d;
            if (up <= max) {
                BlockPos p = new BlockPos(pos.getX(), up, pos.getZ());
                if (safe(level, p)) return p;
            }
            if (d > 0 && down >= min) {
                BlockPos p = new BlockPos(pos.getX(), down, pos.getZ());
                if (safe(level, p)) return p;
            }
            if (up > max && down < min) break;
        }
        return null;
    }

    private Safety() {}
}
