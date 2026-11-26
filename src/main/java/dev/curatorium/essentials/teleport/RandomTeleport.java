package dev.curatorium.essentials.teleport;

import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.JsonFile;
import dev.curatorium.essentials.data.Location;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Tasks;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * /tpr [player]: a random safe spot within a ring around a centre, per dimension (set with
 * /settpr, kept in {@code tpr.json}). It tries one spot per tick so loading new chunks doesn't
 * stall the server, avoids water, lava and (optionally) claims, and stays inside the world border.
 */
public final class RandomTeleport {
    private static final String FILE = "tpr.json";

    /** One dimension's area. Missing values use the config defaults; a missing centre is the world spawn. */
    static final class Area {
        Integer centerX, centerZ, minRange, maxRange;
    }

    private static Map<String, Area> areas = new TreeMap<>();
    private static final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    public static void register() {
        CommandRegistry.add(new Tpr());
        CommandRegistry.add(new SetTpr());
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(ServerStartedEvent.class, e -> {
            Map<String, Area> loaded = JsonFile.read(FILE, new TypeToken<TreeMap<String, Area>>() {}.getType(), () -> new TreeMap<String, Area>());
            areas = new TreeMap<>(loaded);
            searching.clear();
        });
    }

    static Area area(ServerLevel level) {
        return areas.computeIfAbsent(level.dimension().location().toString(), k -> new Area());
    }

    static int min(Area a) {
        return a.minRange != null ? a.minRange : EssConfig.TPR_MIN.get();
    }

    static int max(Area a) {
        return a.maxRange != null ? a.maxRange : EssConfig.TPR_MAX.get();
    }

    static BlockPos center(ServerLevel level, Area a) {
        BlockPos s = level.getSharedSpawnPos();
        return new BlockPos(a.centerX != null ? a.centerX : s.getX(), 0, a.centerZ != null ? a.centerZ : s.getZ());
    }

    /** A spot to stand at (x, z) in this level, or null if it's water, lava, a claim, or has no ground. */
    static BlockPos tryAt(ServerLevel level, int x, int z) {
        if (!level.getWorldBorder().isWithinBounds(x, z)) return null;
        level.getChunk(x >> 4, z >> 4); // loads (or generates) the chunk
        BlockPos feet;
        if (level.dimensionType().hasCeiling()) {
            feet = null;
            for (int y = Math.min(level.getLogicalHeight() - 2, 120); y > level.getMinBuildHeight() + 1; y--) {
                BlockPos p = new BlockPos(x, y, z);
                if (Safety.safe(level, p)) {
                    feet = p;
                    break;
                }
            }
            if (feet == null) return null;
        } else {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinBuildHeight() + 1) return null;
            feet = new BlockPos(x, y, z);
        }
        BlockState ground = level.getBlockState(feet.below());
        if (!ground.getFluidState().isEmpty() && (ground.getFluidState().is(FluidTags.WATER) || ground.getFluidState().is(FluidTags.LAVA))) return null;
        if (!Safety.safe(level, feet)) return null;
        if (EssConfig.TPR_AVOID_CLAIMS.get() && ClaimsHook.inClaim(level, feet)) return null;
        return feet;
    }

    static void search(ServerPlayer p, ServerLevel level, int attemptsLeft) {
        if (p.hasDisconnected()) {
            searching.remove(p.getUUID());
            return;
        }
        Area a = area(level);
        RandomSource r = level.random;
        BlockPos c = center(level, a);
        int min = min(a), max = Math.max(min + 1, max(a));
        double angle = r.nextDouble() * Math.PI * 2;
        // Uniform over the ring's area, not bunched near the centre.
        double dist = Math.sqrt(min * (double) min + r.nextDouble() * ((double) max * max - (double) min * min));
        int x = c.getX() + (int) Math.round(Math.cos(angle) * dist), z = c.getZ() + (int) Math.round(Math.sin(angle) * dist);
        BlockPos found = tryAt(level, x, z);
        if (found != null) {
            searching.remove(p.getUUID());
            Location dest = new Location(level.dimension().location().toString(), found.getX() + 0.5, found.getY(), found.getZ() + 0.5, p.getYRot(), 0);
            Teleports.go(p, dest, true, Messages.get("tpr.arrived", found.getX(), found.getY(), found.getZ()));
            return;
        }
        if (attemptsLeft <= 1) {
            searching.remove(p.getUUID());
            p.sendSystemMessage(Messages.get("tpr.failed"));
            return;
        }
        Tasks.later(1, () -> search(p, level, attemptsLeft - 1));
    }

    static final class Tpr extends EssCommand {
        Tpr() {
            super("tpr", Perms.OP, "etpr", "rtp", "ertp", "randomtp", "erandomtp", "wild", "ewild");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                if (!searching.add(p.getUUID())) return fail(ctx, Messages.get("tpr.busy"));
                reply(ctx, Messages.get("tpr.searching"));
                search(p, p.serverLevel(), EssConfig.TPR_ATTEMPTS.get());
                return 1;
            });
        }
    }

    /** /settpr shows this dimension's area; /settpr center | minrange <n> | maxrange <n> | reset changes it. */
    static final class SetTpr extends EssCommand {
        SetTpr() {
            super("settpr", Perms.OP, "esettpr", "settprlocation", "esettprlocation", "setrtp", "esetrtp");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(this::show))
                    .then(Commands.literal("center").executes(exec(ctx -> {
                        ServerPlayer me = self(ctx);
                        Area a = area(me.serverLevel());
                        a.centerX = me.getBlockX();
                        a.centerZ = me.getBlockZ();
                        JsonFile.write(FILE, areas);
                        return show(ctx);
                    })))
                    .then(Commands.literal("minrange").then(Commands.argument("blocks", IntegerArgumentType.integer(0, 1_000_000)).executes(exec(ctx -> {
                        area(ctx.getSource().getLevel()).minRange = IntegerArgumentType.getInteger(ctx, "blocks");
                        JsonFile.write(FILE, areas);
                        return show(ctx);
                    }))))
                    .then(Commands.literal("maxrange").then(Commands.argument("blocks", IntegerArgumentType.integer(1, 1_000_000)).executes(exec(ctx -> {
                        area(ctx.getSource().getLevel()).maxRange = IntegerArgumentType.getInteger(ctx, "blocks");
                        JsonFile.write(FILE, areas);
                        return show(ctx);
                    }))))
                    .then(Commands.literal("reset").executes(exec(ctx -> {
                        areas.remove(ctx.getSource().getLevel().dimension().location().toString());
                        JsonFile.write(FILE, areas);
                        return show(ctx);
                    })));
        }

        private int show(CommandContext<CommandSourceStack> ctx) {
            ServerLevel level = ctx.getSource().getLevel();
            Area a = area(level);
            BlockPos c = center(level, a);
            reply(ctx, Messages.get("tpr.area", level.dimension().location().toString(), c.getX(), c.getZ(), min(a), max(a)));
            return 1;
        }
    }

    private RandomTeleport() {}
}
