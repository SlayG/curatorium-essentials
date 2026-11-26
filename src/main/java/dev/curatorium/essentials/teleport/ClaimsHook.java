package dev.curatorium.essentials.teleport;

import dev.curatorium.essentials.CuratoriumEssentials;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Curatorium Claims, looked up by reflection so Essentials builds and runs without it: whether
 * a spot is in a claim, and whether that claim is under siege.
 */
public final class ClaimsHook {
    private static boolean tried;
    private static Method current, claimAt, attacksOn;
    private static Field claimId;

    private static boolean init() {
        if (tried) return current != null;
        tried = true;
        if (!ModList.get().isLoaded("curatorium_claims")) return false;
        try {
            Class<?> manager = Class.forName("dev.curatorium.claims.ClaimManager");
            current = manager.getMethod("current");
            claimAt = manager.getMethod("claimAt", Level.class, BlockPos.class);
            claimId = Class.forName("dev.curatorium.claims.data.Claim").getField("id");
            attacksOn = Class.forName("dev.curatorium.claims.siege.SiegeEngineBlockEntity").getMethod("attacksOn", UUID.class);
            CuratoriumEssentials.LOGGER.info("Curatorium Claims found: teleports respect sieges");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            CuratoriumEssentials.LOGGER.warn("Curatorium Claims is installed but its API has changed; siege checks are off", e);
            current = null;
            return false;
        }
    }

    private static Object claim(Level level, BlockPos pos) throws ReflectiveOperationException {
        Object manager = current.invoke(null);
        return manager == null ? null : claimAt.invoke(manager, level, pos);
    }

    public static boolean inClaim(Level level, BlockPos pos) {
        if (!init()) return false;
        try {
            return claim(level, pos) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static boolean underSiege(Level level, BlockPos pos) {
        if (!init()) return false;
        try {
            Object claim = claim(level, pos);
            if (claim == null) return false;
            return !((List<?>) attacksOn.invoke(null, (UUID) claimId.get(claim))).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private ClaimsHook() {}
}
