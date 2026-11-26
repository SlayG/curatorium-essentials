package dev.curatorium.essentials.teleport;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.data.Location;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Direct teleports (staff), /back and /tptoggle. All op-only by default. */
public final class TeleportCommands {
    public static void register() {
        CommandRegistry.add(new Tp());
        CommandRegistry.add(new TpHere("tphere", false, "etphere", "s", "es"));
        CommandRegistry.add(new TpHere("tpohere", true, "etpohere"));
        CommandRegistry.add(new Tpo());
        CommandRegistry.add(new TpAll());
        CommandRegistry.add(new TpPos());
        CommandRegistry.add(new TpOffline());
        CommandRegistry.add(new Top());
        CommandRegistry.add(new Bottom());
        CommandRegistry.add(new Jump());
        CommandRegistry.add(new WorldCommand());
        CommandRegistry.add(new Back());
        CommandRegistry.add(new TpToggle());
    }

    static Location at(Entity e) {
        return Location.of(e);
    }

    static Location at(ServerLevel level, Vec3 pos, float yaw, float pitch) {
        return new Location(level.dimension().location().toString(), pos.x, pos.y, pos.z, yaw, pitch);
    }

    /** Moves any entity (players through {@link Teleports}, for /back and the siege check). */
    static boolean move(Entity e, Location dest) {
        if (e instanceof ServerPlayer p) return Teleports.go(p, dest, false, null);
        ServerLevel level = dest.level(e.getServer());
        if (level == null) return false;
        return e.teleportTo(level, dest.x(), dest.y(), dest.z(), EnumSet.noneOf(RelativeMovement.class), dest.yaw(), dest.pitch());
    }

    // ---------------------------------------------------------------- tp

    /**
     * /tp in vanilla's forms (selectors work): /tp <destination>, /tp <x y z>, /tp <targets>
     * <destination>, /tp <targets> <x y z> [<yaw pitch>]. Moving anyone else needs
     * essentials.tp.others. A player with /tptoggle off can't be teleported to.
     */
    static final class Tp extends EssCommand {
        Tp() {
            super("tp", Perms.OP, "etp", "teleport", "eteleport", "tele", "etele", "tp2p", "etp2p");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("location", Vec3Argument.vec3())
                            .executes(exec(ctx -> toPos(ctx, List.of(self(ctx)), Vec3Argument.getVec3(ctx, "location"), null))))
                    .then(Commands.argument("destination", EntityArgument.entity())
                            .executes(exec(ctx -> toEntity(ctx, List.of(self(ctx)), EntityArgument.getEntity(ctx, "destination")))))
                    .then(Commands.argument("targets", EntityArgument.entities())
                            .requires(src -> has(src, "others", Perms.OP))
                            .then(Commands.argument("location", Vec3Argument.vec3())
                                    .executes(exec(ctx -> toPos(ctx, EntityArgument.getEntities(ctx, "targets"), Vec3Argument.getVec3(ctx, "location"), null)))
                                    .then(Commands.argument("rotation", RotationArgument.rotation())
                                            .executes(exec(ctx -> toPos(ctx, EntityArgument.getEntities(ctx, "targets"), Vec3Argument.getVec3(ctx, "location"),
                                                    RotationArgument.getRotation(ctx, "rotation").getRotation(ctx.getSource()))))))
                            .then(Commands.argument("destination", EntityArgument.entity())
                                    .executes(exec(ctx -> toEntity(ctx, EntityArgument.getEntities(ctx, "targets"), EntityArgument.getEntity(ctx, "destination"))))));
        }

        private int toEntity(CommandContext<CommandSourceStack> ctx, Collection<? extends Entity> targets, Entity dest) {
            ServerPlayer me = ctx.getSource().getPlayer();
            if (dest instanceof ServerPlayer p && !Teleports.accepts(p, me)) return fail(ctx, Messages.get("teleport.targetToggled", p.getDisplayName()));
            int n = 0;
            for (Entity e : targets) {
                if (e instanceof ServerPlayer p && p != me && !Teleports.accepts(p, me)) {
                    ctx.getSource().sendFailure(Messages.get("teleport.targetToggled", p.getDisplayName()));
                    continue;
                }
                if (!move(e, at(dest))) {
                    if (e instanceof ServerPlayer p && p != me) ctx.getSource().sendFailure(Teleports.failedFor(p));
                    continue;
                }
                n++;
            }
            if (n == 0) return 0;
            reply(ctx, targets.size() == 1 ? Messages.get("tp.toEntity", targets.iterator().next().getDisplayName(), dest.getDisplayName())
                    : Messages.get("tp.manyToEntity", n, dest.getDisplayName()));
            return n;
        }

        private int toPos(CommandContext<CommandSourceStack> ctx, Collection<? extends Entity> targets, Vec3 pos, Vec2 rot) {
            ServerPlayer me = ctx.getSource().getPlayer();
            ServerLevel level = ctx.getSource().getLevel();
            int n = 0;
            for (Entity e : targets) {
                if (e instanceof ServerPlayer p && p != me && !Teleports.accepts(p, me)) {
                    ctx.getSource().sendFailure(Messages.get("teleport.targetToggled", p.getDisplayName()));
                    continue;
                }
                float yaw = rot == null ? e.getYRot() : rot.y, pitch = rot == null ? e.getXRot() : rot.x;
                if (!move(e, at(level, pos, yaw, pitch))) {
                    if (e instanceof ServerPlayer p && p != me) ctx.getSource().sendFailure(Teleports.failedFor(p));
                    continue;
                }
                n++;
            }
            if (n == 0) return 0;
            String where = String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", pos.x, pos.y, pos.z);
            reply(ctx, targets.size() == 1 ? Messages.get("tp.toPos", targets.iterator().next().getDisplayName(), where)
                    : Messages.get("tp.manyToPos", n, where));
            return n;
        }
    }

    /** /tphere <player> (/tpohere ignores /tptoggle). */
    static final class TpHere extends EssCommand {
        private final boolean override;

        TpHere(String name, boolean override, String... aliases) {
            super(name, Perms.OP, aliases);
            this.override = override;
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                ServerPlayer p = online(ctx, "player");
                if (p == me) return fail(ctx, Messages.get("teleport.self"));
                if (!override && !Teleports.accepts(p, me)) return fail(ctx, Messages.get("teleport.targetToggled", p.getDisplayName()));
                if (!Teleports.go(p, at(me), false, Messages.get("tp.broughtYou", me.getDisplayName()))) return fail(ctx, Teleports.failedFor(p));
                reply(ctx, Messages.get("tp.brought", p.getDisplayName()));
                return 1;
            })));
        }
    }

    /** /tpo <player> [player]: as /tp, ignoring /tptoggle. */
    static final class Tpo extends EssCommand {
        Tpo() {
            super("tpo", Perms.OP, "etpo");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> {
                        ServerPlayer me = self(ctx);
                        ServerPlayer p = online(ctx, "player");
                        if (!Teleports.go(me, at(p), false, null)) return 0;
                        reply(ctx, Messages.get("tp.toEntity", me.getDisplayName(), p.getDisplayName()));
                        return 1;
                    }))
                    .then(playerArg("to").executes(exec(ctx -> {
                        ServerPlayer p = online(ctx, "player"), to = online(ctx, "to");
                        if (!Teleports.go(p, at(to), false, null)) return fail(ctx, Teleports.failedFor(p));
                        reply(ctx, Messages.get("tp.toEntity", p.getDisplayName(), to.getDisplayName()));
                        return 1;
                    }))));
        }
    }

    /** /tpall [player]: everyone to you (or to that player). */
    static final class TpAll extends EssCommand {
        TpAll() {
            super("tpall", Perms.OP, "etpall");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> all(ctx, self(ctx))))
                    .then(playerArg("player").executes(exec(ctx -> all(ctx, online(ctx, "player")))));
        }

        private int all(CommandContext<CommandSourceStack> ctx, ServerPlayer to) {
            int n = 0;
            for (ServerPlayer p : List.copyOf(to.server.getPlayerList().getPlayers())) {
                if (p == to || !Teleports.accepts(p, ctx.getSource().getPlayer())) continue;
                if (Teleports.go(p, at(to), false, Messages.get("tp.broughtYou", to.getDisplayName()))) n++;
            }
            reply(ctx, Messages.get("tpall.done", n, to.getDisplayName()));
            return 1;
        }
    }

    /** /tppos <x y z> [yaw pitch] [dimension] */
    static final class TpPos extends EssCommand {
        TpPos() {
            super("tppos", Perms.OP, "etppos");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            var pos = Commands.argument("location", Vec3Argument.vec3());
            root.then(pos.executes(exec(ctx -> go(ctx, null, null)))
                    .then(Commands.argument("dimension", DimensionArgument.dimension())
                            .executes(exec(ctx -> go(ctx, null, DimensionArgument.getDimension(ctx, "dimension")))))
                    .then(Commands.argument("rotation", RotationArgument.rotation())
                            .executes(exec(ctx -> go(ctx, RotationArgument.getRotation(ctx, "rotation").getRotation(ctx.getSource()), null)))
                            .then(Commands.argument("dimension", DimensionArgument.dimension())
                                    .executes(exec(ctx -> go(ctx, RotationArgument.getRotation(ctx, "rotation").getRotation(ctx.getSource()),
                                            DimensionArgument.getDimension(ctx, "dimension")))))));
        }

        private int go(CommandContext<CommandSourceStack> ctx, Vec2 rot, ServerLevel level) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            Vec3 pos = Vec3Argument.getVec3(ctx, "location");
            ServerLevel target = level != null ? level : me.serverLevel();
            Location dest = at(target, pos, rot == null ? me.getYRot() : rot.y, rot == null ? me.getXRot() : rot.x);
            if (!Teleports.go(me, dest, false, null)) return 0;
            reply(ctx, Messages.get("tp.toPos", me.getDisplayName(), String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", pos.x, pos.y, pos.z)));
            return 1;
        }
    }

    /** /tpoffline <player>: where they logged out. */
    static final class TpOffline extends EssCommand {
        TpOffline() {
            super("tpoffline", Perms.OP, "etpoffline", "tpoff", "etpoff", "tplogout", "etplogout");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                PlayerLookup.Target t = known(ctx, "player");
                Location dest = t.isOnline() ? at(t.online()) : UserStore.get(t.uuid()).logoutLocation;
                if (dest == null) return fail(ctx, Messages.get("tpoffline.unknown", t.name()));
                if (!Teleports.go(me, dest, false, null)) return 0;
                reply(ctx, Messages.get(t.isOnline() ? "tp.toEntity" : "tpoffline.done", me.getDisplayName(), t.name()));
                return 1;
            })));
        }
    }

    // ---------------------------------------------------------------- top, bottom, jump, world

    static final class Top extends EssCommand {
        Top() {
            super("top", Perms.OP, "etop");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                ServerLevel level = me.serverLevel();
                int x = me.getBlockX(), z = me.getBlockZ();
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (level.dimensionType().hasCeiling()) {
                    // In the Nether the heightmap is the roof; find the highest open space below it instead.
                    BlockPos found = null;
                    for (int yy = level.getLogicalHeight() - 2; yy > me.getBlockY(); yy--) {
                        if (Safety.safe(level, new BlockPos(x, yy, z))) {
                            found = new BlockPos(x, yy, z);
                            break;
                        }
                    }
                    if (found == null) return fail(ctx, Messages.get("teleport.unsafe"));
                    y = found.getY();
                }
                if (!Teleports.go(me, new Location(level.dimension().location().toString(), x + 0.5, y, z + 0.5, me.getYRot(), me.getXRot()), false, null)) return 0;
                reply(ctx, Messages.get("top.done"));
                return 1;
            }));
        }
    }

    static final class Bottom extends EssCommand {
        Bottom() {
            super("bottom", Perms.OP, "ebottom");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                ServerLevel level = me.serverLevel();
                int x = me.getBlockX(), z = me.getBlockZ();
                for (int y = level.getMinBuildHeight() + 1; y < me.getBlockY(); y++) {
                    if (Safety.safe(level, new BlockPos(x, y, z))) {
                        if (!Teleports.go(me, new Location(level.dimension().location().toString(), x + 0.5, y, z + 0.5, me.getYRot(), me.getXRot()), false, null)) return 0;
                        reply(ctx, Messages.get("bottom.done"));
                        return 1;
                    }
                }
                return fail(ctx, Messages.get("bottom.none"));
            }));
        }
    }

    /** /jump: onto the block you're looking at. */
    static final class Jump extends EssCommand {
        Jump() {
            super("jump", Perms.OP, "ejump", "j", "ej", "jumpto", "ejumpto");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                HitResult hit = me.pick(200, 1f, false);
                if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) return fail(ctx, Messages.get("world.lookAtBlock"));
                BlockPos top = b.getBlockPos().above();
                ServerLevel level = me.serverLevel();
                while (top.getY() < level.getMaxBuildHeight() - 1 && !Safety.safe(level, top)) top = top.above();
                Location dest = new Location(level.dimension().location().toString(), top.getX() + 0.5, top.getY(), top.getZ() + 0.5, me.getYRot(), me.getXRot());
                if (!Teleports.go(me, dest, false, null)) return 0;
                return 1;
            }));
        }
    }

    /** /world <dimension>: the same spot in another dimension (scaled for the Nether), or the End's platform. */
    static final class WorldCommand extends EssCommand {
        WorldCommand() {
            super("world", Perms.OP, "eworld");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("dimension", DimensionArgument.dimension()).executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                ServerLevel to = DimensionArgument.getDimension(ctx, "dimension");
                if (to == me.serverLevel()) return fail(ctx, Messages.get("world.already"));
                Location dest;
                if (to.dimension() == Level.END) {
                    BlockPos p = ServerLevel.END_SPAWN_POINT;
                    dest = new Location(to.dimension().location().toString(), p.getX() + 0.5, p.getY(), p.getZ() + 0.5, me.getYRot(), me.getXRot());
                } else {
                    double scale = me.serverLevel().dimensionType().coordinateScale() / to.dimensionType().coordinateScale();
                    double x = me.getX() * scale, z = me.getZ() * scale;
                    int y = to.dimensionType().hasCeiling() ? Math.min(me.getBlockY(), to.getLogicalHeight() - 2)
                            : to.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
                    if (me.serverLevel().dimension() == Level.END) {
                        // Leaving the End: go to the overworld spawn rather than (0, 0).
                        BlockPos s = to.getSharedSpawnPos();
                        x = s.getX() + 0.5;
                        z = s.getZ() + 0.5;
                        y = to.getHeight(Heightmap.Types.MOTION_BLOCKING, s.getX(), s.getZ());
                    }
                    dest = new Location(to.dimension().location().toString(), x, y, z, me.getYRot(), me.getXRot());
                    if (Safety.find(to, dest) == null) {
                        // Nothing near: try the whole column from the top down (the Nether's caves).
                        dest = new Location(dest.dimension(), x, to.dimensionType().hasCeiling() ? 64 : y, z, me.getYRot(), me.getXRot());
                    }
                }
                if (!Teleports.go(me, dest, false, null)) return 0;
                reply(ctx, Messages.get("world.done", to.dimension().location().toString()));
                return 1;
            })));
        }
    }

    // ---------------------------------------------------------------- back, tptoggle

    /** /back [player]: to where you were before your last teleport (or where you died). */
    static final class Back extends EssCommand {
        Back() {
            super("back", Perms.OP, "eback", "return", "ereturn");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                Location back = UserStore.get(p).lastLocation;
                if (back == null) return fail(ctx, Messages.get("back.none"));
                if (!Teleports.go(p, back, true, Messages.get("back.done"))) return 0;
                tellOther(ctx, p, Messages.get("back.other", p.getDisplayName()));
                return 1;
            });
        }
    }

    /** /tptoggle [player]: stop (or allow) others teleporting to you and sending you requests. */
    static final class TpToggle extends EssCommand {
        TpToggle() {
            super("tptoggle", Perms.OP, "etptoggle");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                UserData d = UserStore.get(p);
                d.teleportEnabled = !d.teleportEnabled;
                d.markDirty();
                Component state = Messages.get(d.teleportEnabled ? "toggle.on" : "toggle.off");
                p.sendSystemMessage(Messages.get("tptoggle.state", state));
                tellOther(ctx, p, Messages.get("toggle.other", Messages.get("tptoggle.state", state), p.getDisplayName()));
                return 1;
            });
        }
    }

    private TeleportCommands() {}
}
