package dev.curatorium.essentials.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Tasks;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The fun ones. All op-only by default; any can be turned off with commands.disabled. */
public final class FunCommands {
    public static void register() {
        CommandRegistry.add(new Beezooka());
        CommandRegistry.add(new KittyCannon());
        CommandRegistry.add(new Fireball());
        CommandRegistry.add(new Firework());
        CommandRegistry.add(new Nuke());
        CommandRegistry.add(new Antioch());
        CommandRegistry.add(new Ice());
        CommandRegistry.add(new Break());
    }

    /** Launches a living "projectile" from the player's eyes that pops (no block damage) a second later. */
    static void launch(ServerPlayer me, Entity e) {
        Vec3 look = me.getLookAngle();
        e.moveTo(me.getX(), me.getEyeY(), me.getZ(), me.getYRot(), me.getXRot());
        e.setDeltaMovement(look.scale(2));
        me.level().addFreshEntity(e);
        Tasks.later(20, () -> {
            if (e.isRemoved()) return;
            e.level().explode(null, e.getX(), e.getY(), e.getZ(), 0f, Level.ExplosionInteraction.NONE);
            e.discard();
        });
    }

    static final class Beezooka extends EssCommand {
        Beezooka() {
            super("beezooka", Perms.OP, "ebeezooka", "beecannon", "ebeecannon");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                Bee bee = EntityType.BEE.create(me.level());
                if (bee == null) return 0;
                bee.setNoAi(true);
                launch(me, bee);
                return 1;
            }));
        }
    }

    static final class KittyCannon extends EssCommand {
        KittyCannon() {
            super("kittycannon", Perms.OP, "ekittycannon");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                Cat cat = EntityType.CAT.create(me.level());
                if (cat == null) return 0;
                cat.finalizeSpawn(me.serverLevel(), me.serverLevel().getCurrentDifficultyAt(me.blockPosition()),
                        net.minecraft.world.entity.MobSpawnType.COMMAND, null);
                cat.setBaby(true);
                cat.setNoAi(true);
                launch(me, cat);
                return 1;
            }));
        }
    }

    /** /fireball [type] [speed] */
    static final class Fireball extends EssCommand {
        private static final List<String> TYPES = List.of("fireball", "small", "large", "arrow", "skull", "dragon", "egg",
                "snowball", "expbottle");

        Fireball() {
            super("fireball", Perms.OP, "efireball", "fireentity", "efireentity", "fireskull", "efireskull");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> fire(ctx, "fireball", 2)))
                    .then(Commands.argument("type", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(TYPES, b))
                            .executes(exec(ctx -> fire(ctx, str(ctx, "type"), 2)))
                            .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.1, 5))
                                    .executes(exec(ctx -> fire(ctx, str(ctx, "type"), DoubleArgumentType.getDouble(ctx, "speed"))))));
        }

        private int fire(CommandContext<CommandSourceStack> ctx, String type, double speed) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            String t = type.toLowerCase(Locale.ROOT);
            if (!TYPES.contains(t)) return fail(ctx, Messages.get("fireball.unknown", type, String.join(", ", TYPES)));
            if (!t.equals("fireball") && !has(ctx.getSource(), t, Perms.OP)) return fail(ctx, Messages.get("command.noPermission"));
            Level level = me.level();
            Vec3 dir = me.getLookAngle();
            Vec3 start = me.getEyePosition().add(dir.scale(1.5));
            Projectile p = switch (t) {
                case "small" -> new SmallFireball(level, me, dir);
                case "large" -> new LargeFireball(level, me, dir, 3);
                case "skull" -> new WitherSkull(level, me, dir);
                case "dragon" -> new DragonFireball(level, me, dir);
                case "arrow" -> {
                    Arrow a = new Arrow(level, me, new ItemStack(Items.ARROW), null);
                    a.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
                    yield a;
                }
                case "egg" -> new ThrownEgg(level, me);
                case "snowball" -> new Snowball(level, me);
                case "expbottle" -> new ThrownExperienceBottle(level, me);
                default -> new LargeFireball(level, me, dir, 1);
            };
            p.setPos(start.x, start.y, start.z);
            if (p instanceof net.minecraft.world.entity.projectile.AbstractHurtingProjectile h) {
                h.setDeltaMovement(dir.scale(speed));
                h.accelerationPower = 0.1 * speed;
            } else {
                p.shoot(dir.x, dir.y, dir.z, (float) speed, 0f);
            }
            level.addFreshEntity(p);
            return 1;
        }
    }

    /**
     * /firework: launches a random firework above you. /firework fire [amount] launches copies of
     * the held rocket; /firework power <0-3> sets its flight; /firework clear removes its effects.
     */
    static final class Firework extends EssCommand {
        Firework() {
            super("firework", Perms.OP, "efirework");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                rocket(me, random(me.getRandom()));
                return 1;
            })).then(Commands.literal("fire").requires(src -> has(src, "fire", Perms.OP))
                    .executes(exec(ctx -> fireHeld(ctx, 1)))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 50))
                            .executes(exec(ctx -> fireHeld(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
                    .then(Commands.literal("power").then(Commands.argument("power", IntegerArgumentType.integer(0, 3)).executes(exec(ctx -> {
                        ItemStack held = heldRocket(ctx);
                        Fireworks f = held.getOrDefault(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
                        held.set(DataComponents.FIREWORKS, new Fireworks(IntegerArgumentType.getInteger(ctx, "power"), f.explosions()));
                        reply(ctx, Messages.get("firework.power", IntegerArgumentType.getInteger(ctx, "power")));
                        return 1;
                    }))))
                    .then(Commands.literal("clear").executes(exec(ctx -> {
                        ItemStack held = heldRocket(ctx);
                        Fireworks f = held.getOrDefault(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
                        held.set(DataComponents.FIREWORKS, new Fireworks(f.flightDuration(), List.of()));
                        reply(ctx, Messages.get("firework.cleared"));
                        return 1;
                    })));
        }

        private static ItemStack heldRocket(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            ItemStack held = self(ctx).getMainHandItem();
            if (!held.is(Items.FIREWORK_ROCKET)) throw ItemCommands.EssCommandErrors.error("firework.holdRocket");
            return held;
        }

        private int fireHeld(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            ItemStack held = heldRocket(ctx);
            for (int i = 0; i < amount; i++) rocket(me, held.copyWithCount(1));
            return 1;
        }

        private static void rocket(ServerPlayer me, ItemStack stack) {
            me.level().addFreshEntity(new FireworkRocketEntity(me.level(), me.getX(), me.getY() + 1, me.getZ(), stack));
        }

        static ItemStack random(RandomSource r) {
            FireworkExplosion.Shape[] shapes = FireworkExplosion.Shape.values();
            DyeColor[] colours = DyeColor.values();
            IntList c = IntList.of(colours[r.nextInt(colours.length)].getFireworkColor(), colours[r.nextInt(colours.length)].getFireworkColor());
            IntList fade = IntList.of(colours[r.nextInt(colours.length)].getFireworkColor());
            ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
            stack.set(DataComponents.FIREWORKS, new Fireworks(1 + r.nextInt(2), List.of(
                    new FireworkExplosion(shapes[r.nextInt(shapes.length)], c, fade, r.nextBoolean(), r.nextBoolean()))));
            return stack;
        }
    }

    /** /nuke [players]: rains primed TNT around the players (everyone if none are named). */
    static final class Nuke extends EssCommand {
        Nuke() {
            super("nuke", Perms.ADMIN, "enuke");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> nuke(ctx, ctx.getSource().getServer().getPlayerList().getPlayers())))
                    .then(Commands.argument("players", EntityArgument.players())
                            .executes(exec(ctx -> nuke(ctx, EntityArgument.getPlayers(ctx, "players")))));
        }

        private int nuke(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
            int n = 0;
            for (ServerPlayer p : new ArrayList<>(targets)) {
                if (Perms.has(p, "essentials.nuke.exempt", Perms.ADMIN) && p != ctx.getSource().getPlayer()) continue;
                p.sendSystemMessage(Messages.get("nuke.incoming"));
                for (int x = -10; x <= 10; x += 5) {
                    for (int z = -10; z <= 10; z += 5) {
                        PrimedTnt tnt = new PrimedTnt(p.level(), p.getX() + x, Math.min(p.getY() + 20, p.level().getMaxBuildHeight() - 1),
                                p.getZ() + z, null);
                        tnt.setFuse(60);
                        p.level().addFreshEntity(tnt);
                    }
                }
                n++;
            }
            reply(ctx, Messages.get("nuke.done", n));
            return 1;
        }
    }

    /** /antioch: primed TNT where you look. */
    static final class Antioch extends EssCommand {
        Antioch() {
            super("antioch", Perms.ADMIN, "eantioch", "grenade", "egrenade", "tnt", "etnt");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                BlockPos pos = WorldCommands.lookingAt(me, 100).getBlockPos().above();
                PrimedTnt tnt = new PrimedTnt(me.level(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, me);
                me.level().addFreshEntity(tnt);
                ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(Messages.get("antioch.broadcast"), false);
                return 1;
            }));
        }
    }

    /** /ice [player]: freezes, as powder snow does. */
    static final class Ice extends EssCommand {
        Ice() {
            super("ice", Perms.OP, "eice", "efreeze", "freeze");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                p.setTicksFrozen(p.getTicksRequiredToFreeze() * 3);
                p.sendSystemMessage(Messages.get("ice.frozen"));
                tellOther(ctx, p, Messages.get("ice.frozeOther", p.getDisplayName()));
                return 1;
            });
        }
    }

    /** /break: removes the block you look at (bedrock needs essentials.break.bedrock). */
    static final class Break extends EssCommand {
        Break() {
            super("break", Perms.ADMIN, "ebreak");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                BlockPos pos = WorldCommands.lookingAt(me, 20).getBlockPos();
                ServerLevel level = me.serverLevel();
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.BEDROCK) && !has(ctx.getSource(), "bedrock", Perms.ADMIN)) return fail(ctx, Messages.get("break.bedrock"));
                // Let protection (Curatorium Claims) have its say.
                var event = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level, pos, state, me);
                if (net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event).isCanceled()) return fail(ctx, Messages.get("break.denied"));
                level.destroyBlock(pos, false, me);
                return 1;
            }));
        }
    }

    private FunCommands() {}
}
