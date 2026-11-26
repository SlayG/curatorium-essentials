package dev.curatorium.essentials.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.CuratoriumEssentials;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Durations;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

/** Time, weather, lightning, entities, trees, server status and backups. */
public final class WorldCommands {
    public static void register() {
        CommandRegistry.add(new Time());
        CommandRegistry.add(new Weather());
        CommandRegistry.add(new Thunder());
        CommandRegistry.add(new Lightning());
        CommandRegistry.add(new Remove());
        CommandRegistry.add(new SpawnMob());
        CommandRegistry.add(new Spawner());
        CommandRegistry.add(new Tree(false));
        CommandRegistry.add(new Tree(true));
        CommandRegistry.add(new Gc());
        CommandRegistry.add(new Backup());
    }

    public static void registerEvents(IEventBus bus) {
    }

    static ServerLevel levelOf(CommandSourceStack src) {
        return src.getLevel();
    }

    /** What the player is looking at, up to {@code range} blocks away. */
    static BlockHitResult lookingAt(ServerPlayer p, double range) throws CommandSyntaxException {
        HitResult hit = p.pick(range, 1f, false);
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) return b;
        throw ItemCommands.EssCommandErrors.error("world.lookAtBlock");
    }

    // ---------------------------------------------------------------- time

    private static final Map<String, Long> NAMED_TIMES = new LinkedHashMap<>();
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?");

    static {
        NAMED_TIMES.put("sunrise", 23000L);
        NAMED_TIMES.put("dawn", 23000L);
        NAMED_TIMES.put("day", 0L);
        NAMED_TIMES.put("daystart", 0L);
        NAMED_TIMES.put("morning", 1000L);
        NAMED_TIMES.put("noon", 6000L);
        NAMED_TIMES.put("midday", 6000L);
        NAMED_TIMES.put("afternoon", 9000L);
        NAMED_TIMES.put("sunset", 12000L);
        NAMED_TIMES.put("dusk", 12000L);
        NAMED_TIMES.put("night", 14000L);
        NAMED_TIMES.put("nightstart", 14000L);
        NAMED_TIMES.put("midnight", 18000L);
    }

    /** "day", "night"…, "1000", "1000t", "1000ticks", "17:30", "4pm", "10s", "1d" (vanilla's day): ticks, or empty. */
    static OptionalLong parseTime(String input) {
        String s = input.toLowerCase(Locale.ROOT).trim();
        Long named = NAMED_TIMES.get(s);
        if (named != null) return OptionalLong.of(named);
        try {
            if (s.endsWith("ticks")) return OptionalLong.of(Long.parseLong(s.substring(0, s.length() - 5)));
            if (s.endsWith("t")) return OptionalLong.of(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("d")) return OptionalLong.of(Long.parseLong(s.substring(0, s.length() - 1)) * 24000);
            if (s.endsWith("s")) return OptionalLong.of(Long.parseLong(s.substring(0, s.length() - 1)) * 20);
            if (s.matches("\\d+")) return OptionalLong.of(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        Matcher m = CLOCK.matcher(s);
        if (m.matches() && (m.group(2) != null || m.group(3) != null)) {
            int h = Integer.parseInt(m.group(1));
            int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (m.group(3) != null) {
                if (h < 1 || h > 12) return OptionalLong.empty();
                h = h % 12 + (m.group(3).equals("pm") ? 12 : 0);
            }
            if (h > 23 || min > 59) return OptionalLong.empty();
            // Tick 0 is 6:00.
            return OptionalLong.of(((h - 6 + 24) % 24) * 1000L + Math.round(min * 1000 / 60.0));
        }
        return OptionalLong.empty();
    }

    /** "14:30 (8500 ticks)" */
    static String describe(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        long hours = (t / 1000 + 6) % 24, minutes = (t % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "%02d:%02d (%d ticks)", hours, minutes, t);
    }

    static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> TIME_NAMES =
            (c, b) -> SharedSuggestionProvider.suggest(NAMED_TIMES.keySet(), b);

    /** /time [set|add] <time>, /time query daytime|gametime|day, /time (shows it). Changes every world, as vanilla does. */
    static final class Time extends EssCommand {
        Time() {
            super("time", Perms.OP, "etime", "day", "eday", "night", "enight");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                String label = ctx.getNodes().get(0).getNode().getName();
                if (label.endsWith("day")) return set(ctx, 0, false);
                if (label.endsWith("night")) return set(ctx, 14000, false);
                ServerLevel level = levelOf(ctx.getSource());
                reply(ctx, Messages.get("time.current", describe(level.getDayTime()), level.getDayTime() / 24000));
                return 1;
            }));
            root.then(Commands.literal("set").requires(src -> has(src, "set", Perms.OP))
                    .then(Commands.argument("time", StringArgumentType.greedyString()).suggests(TIME_NAMES)
                            .executes(exec(ctx -> set(ctx, parse(ctx), false)))));
            root.then(Commands.literal("add").requires(src -> has(src, "add", Perms.OP))
                    .then(Commands.argument("time", StringArgumentType.greedyString())
                            .executes(exec(ctx -> set(ctx, parse(ctx), true)))));
            root.then(Commands.literal("query")
                    .then(Commands.literal("daytime").executes(exec(ctx -> query(ctx, levelOf(ctx.getSource()).getDayTime() % 24000))))
                    .then(Commands.literal("gametime").executes(exec(ctx -> query(ctx, levelOf(ctx.getSource()).getGameTime() % Integer.MAX_VALUE))))
                    .then(Commands.literal("day").executes(exec(ctx -> query(ctx, levelOf(ctx.getSource()).getDayTime() / 24000 % Integer.MAX_VALUE)))));
            root.then(Commands.argument("time", StringArgumentType.greedyString()).suggests(TIME_NAMES)
                    .requires(src -> has(src, "set", Perms.OP))
                    .executes(exec(ctx -> set(ctx, parse(ctx), false))));
        }

        private static long parse(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            String input = str(ctx, "time");
            OptionalLong t = parseTime(input);
            if (t.isEmpty()) throw ItemCommands.EssCommandErrors.error("time.invalid", input);
            return t.getAsLong();
        }

        private static int query(CommandContext<CommandSourceStack> ctx, long value) {
            reply(ctx, Messages.get("time.query", value));
            return (int) value;
        }

        private int set(CommandContext<CommandSourceStack> ctx, long ticks, boolean add) {
            MinecraftServer server = ctx.getSource().getServer();
            long shown = 0;
            for (ServerLevel level : server.getAllLevels()) {
                long now = level.getDayTime();
                long target;
                if (add) {
                    target = now + ticks;
                } else {
                    // Forward to the next time it is that time of day, keeping the day count.
                    target = now - Math.floorMod(now, 24000L) + ticks % 24000;
                    if (target < now) target += 24000;
                }
                level.setDayTime(target);
                if (level == ctx.getSource().getLevel()) shown = target;
            }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.connection.send(new ClientboundSetTimePacket(p.level().getGameTime(), p.level().getDayTime(),
                        p.level().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
            }
            reply(ctx, Messages.get("time.set", describe(shown)));
            return 1;
        }
    }

    // ---------------------------------------------------------------- weather

    /** Seconds or a length like 10m; -1 for vanilla's random length. */
    static int durationTicks(String s) throws CommandSyntaxException {
        if (s == null) return -1;
        try {
            return Math.max(1, Integer.parseInt(s)) * 20;
        } catch (NumberFormatException e) {
            OptionalLong d = Durations.parse(s);
            if (d.isEmpty()) throw ItemCommands.EssCommandErrors.error("duration.invalid", s);
            return (int) Math.min(Integer.MAX_VALUE, d.getAsLong() / 50);
        }
    }

    static void setWeather(ServerLevel level, String kind, int ticks) {
        int t = ticks > 0 ? ticks : 6000 + level.random.nextInt(12000);
        switch (kind) {
            case "clear" -> level.setWeatherParameters(t, 0, false, false);
            case "rain" -> level.setWeatherParameters(0, t, true, false);
            default -> level.setWeatherParameters(0, t, true, true);
        }
    }

    static String weatherKind(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "clear", "sun", "sunny" -> "clear";
            case "rain", "storm", "rainy" -> "rain";
            case "thunder", "thunderstorm" -> "thunder";
            default -> null;
        };
    }

    /** /weather <clear|sun|rain|storm|thunder> [seconds or length] */
    static final class Weather extends EssCommand {
        Weather() {
            super("weather", Perms.OP, "eweather", "rain", "erain", "sky", "esky", "sun", "esun");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                String label = ctx.getNodes().get(0).getNode().getName();
                if (label.endsWith("rain")) return set(ctx, "rain", null);
                if (label.endsWith("sun")) return set(ctx, "clear", null);
                ServerLevel level = levelOf(ctx.getSource());
                reply(ctx, Messages.get(level.isThundering() ? "weather.isThunder" : level.isRaining() ? "weather.isRain" : "weather.isClear"));
                return 1;
            })).then(Commands.argument("weather", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("clear", "sun", "rain", "storm", "thunder"), b))
                    .executes(exec(ctx -> set(ctx, str(ctx, "weather"), null)))
                    .then(Commands.argument("duration", StringArgumentType.word())
                            .executes(exec(ctx -> set(ctx, str(ctx, "weather"), str(ctx, "duration"))))));
        }

        private int set(CommandContext<CommandSourceStack> ctx, String input, String duration) throws CommandSyntaxException {
            String kind = weatherKind(input);
            if (kind == null) return fail(ctx, Messages.get("weather.unknown", input));
            setWeather(ctx.getSource().getServer().overworld(), kind, durationTicks(duration));
            reply(ctx, Messages.get("weather." + kind));
            return 1;
        }
    }

    /** /thunder <true|false> [seconds] */
    static final class Thunder extends EssCommand {
        Thunder() {
            super("thunder", Perms.OP, "ethunder");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            var on = Commands.argument("on", BoolArgumentType.bool());
            root.then(on.executes(exec(ctx -> set(ctx, null)))
                    .then(Commands.argument("duration", StringArgumentType.word()).executes(exec(ctx -> set(ctx, str(ctx, "duration"))))));
        }

        private int set(CommandContext<CommandSourceStack> ctx, String duration) throws CommandSyntaxException {
            boolean on = BoolArgumentType.getBool(ctx, "on");
            setWeather(ctx.getSource().getServer().overworld(), on ? "thunder" : "clear", durationTicks(duration));
            reply(ctx, Messages.get(on ? "weather.thunder" : "weather.clear"));
            return 1;
        }
    }

    // ---------------------------------------------------------------- lightning

    /** /lightning strikes where you look; /lightning <player> strikes them. */
    static final class Lightning extends EssCommand {
        Lightning() {
            super("lightning", Perms.OP, "elightning", "shock", "eshock", "smite", "esmite", "strike", "estrike", "thor", "ethor");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                strike(me.serverLevel(), Vec3.atBottomCenterOf(lookingAt(me, 200).getBlockPos().above()));
                return 1;
            })).then(playerArg("player").requires(src -> has(src, "others", Perms.OP)).executes(exec(ctx -> {
                ServerPlayer p = online(ctx, "player");
                if (Perms.has(p, "essentials.lightning.exempt", Perms.ADMIN) && p != ctx.getSource().getPlayer()) {
                    return fail(ctx, Messages.get("lightning.exempt", p.getDisplayName()));
                }
                strike(p.serverLevel(), p.position());
                p.sendSystemMessage(Messages.get("lightning.struck"));
                reply(ctx, Messages.get("lightning.smote", p.getDisplayName()));
                return 1;
            })));
        }

        static void strike(ServerLevel level, Vec3 at) {
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt == null) return;
            bolt.moveTo(at);
            level.addFreshEntity(bolt);
        }
    }

    // ---------------------------------------------------------------- remove

    private static final Map<String, Predicate<Entity>> REMOVE_KINDS = new LinkedHashMap<>();

    static boolean keep(Entity e) {
        return e instanceof Player || e.hasCustomName() || (e instanceof OwnableEntity o && o.getOwnerUUID() != null);
    }

    static {
        REMOVE_KINDS.put("drops", e -> e instanceof ItemEntity);
        REMOVE_KINDS.put("arrows", e -> e instanceof AbstractArrow);
        REMOVE_KINDS.put("xp", e -> e instanceof ExperienceOrb);
        REMOVE_KINDS.put("boats", e -> e instanceof Boat);
        REMOVE_KINDS.put("minecarts", e -> e instanceof AbstractMinecart);
        REMOVE_KINDS.put("paintings", e -> e instanceof Painting);
        REMOVE_KINDS.put("itemframes", e -> e instanceof ItemFrame);
        REMOVE_KINDS.put("endercrystals", e -> e instanceof EndCrystal);
        REMOVE_KINDS.put("monsters", e -> e instanceof Enemy && !keep(e));
        REMOVE_KINDS.put("animals", e -> (e instanceof Animal || e instanceof WaterAnimal) && !keep(e));
        REMOVE_KINDS.put("ambient", e -> e instanceof AmbientCreature && !keep(e));
        REMOVE_KINDS.put("mobs", e -> e instanceof Mob && !keep(e));
        REMOVE_KINDS.put("tamed", e -> e instanceof OwnableEntity o && o.getOwnerUUID() != null);
        REMOVE_KINDS.put("named", e -> e.hasCustomName() && !(e instanceof Player));
        REMOVE_KINDS.put("all", e -> !keep(e) && (e instanceof ItemEntity || e instanceof AbstractArrow || e instanceof ExperienceOrb
                || e instanceof Mob));
    }

    /**
     * /remove <kind> [radius], /remove type <entity> [radius]: removes entities around you (or,
     * without a radius, in the whole dimension). Named and tamed mobs are only removed by the
     * "named" and "tamed" kinds.
     */
    static final class Remove extends EssCommand {
        Remove() {
            super("remove", Perms.OP, "eremove", "butcher", "ebutcher", "killall", "ekillall", "mobkill", "emobkill");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.literal("type").then(Commands.argument("entity", ResourceArgument.resource(buildContext(), Registries.ENTITY_TYPE))
                    .executes(exec(ctx -> remove(ctx, typeFilter(ctx), 0)))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                            .executes(exec(ctx -> remove(ctx, typeFilter(ctx), IntegerArgumentType.getInteger(ctx, "radius")))))));
            root.then(Commands.argument("kind", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(REMOVE_KINDS.keySet(), b))
                    .executes(exec(ctx -> remove(ctx, kind(ctx), 0)))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                            .executes(exec(ctx -> remove(ctx, kind(ctx), IntegerArgumentType.getInteger(ctx, "radius"))))));
        }

        private static Predicate<Entity> typeFilter(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            Holder.Reference<EntityType<?>> type = ResourceArgument.getEntityType(ctx, "entity");
            return e -> e.getType() == type.value() && !(e instanceof Player);
        }

        private static Predicate<Entity> kind(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            Predicate<Entity> p = REMOVE_KINDS.get(str(ctx, "kind").toLowerCase(Locale.ROOT));
            if (p == null) throw ItemCommands.EssCommandErrors.error("remove.unknown", str(ctx, "kind"), String.join(", ", REMOVE_KINDS.keySet()));
            return p;
        }

        private int remove(CommandContext<CommandSourceStack> ctx, Predicate<Entity> filter, int radius) {
            int max = EssConfig.REMOVE_MAX_RADIUS.get();
            if (max > 0 && (radius == 0 || radius > max)) radius = max;
            ServerLevel level = ctx.getSource().getLevel();
            Vec3 center = ctx.getSource().getPosition();
            double r2 = (double) radius * radius;
            List<Entity> found = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e == null || e.isRemoved() || !filter.test(e)) continue;
                if (radius > 0 && e.distanceToSqr(center) > r2) continue;
                found.add(e);
            }
            for (Entity e : found) e.discard();
            reply(ctx, Messages.get("remove.done", found.size()));
            return 1;
        }
    }

    // ---------------------------------------------------------------- spawnmob, spawner

    /** /spawnmob <entity> [amount] [player]: at the block you look at, or at a player. Needs essentials.spawnmob.<entity>. */
    static final class SpawnMob extends EssCommand {
        SpawnMob() {
            super("spawnmob", Perms.OP, "espawnmob", "mob", "emob", "spawnentity", "espawnentity", "mobspawn", "emobspawn");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            var type = Commands.argument("entity", ResourceArgument.resource(buildContext(), Registries.ENTITY_TYPE))
                    .suggests(SuggestionProviders.SUMMONABLE_ENTITIES);
            root.then(type.executes(exec(ctx -> spawn(ctx, 1, null)))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000))
                            .executes(exec(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "amount"), null)))
                            .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                                    .executes(exec(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "amount"), online(ctx, "player")))))));
        }

        private int spawn(CommandContext<CommandSourceStack> ctx, int amount, ServerPlayer at) throws CommandSyntaxException {
            EntityType<?> type = ResourceArgument.getSummonableEntityType(ctx, "entity").value();
            String path = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
            if (!has(ctx.getSource(), path, Perms.OP)) return fail(ctx, Messages.get("spawnmob.noPermission", type.getDescription()));
            int limit = EssConfig.SPAWNMOB_LIMIT.get();
            if (amount > limit && !has(ctx.getSource(), "nolimit", Perms.ADMIN)) {
                ctx.getSource().sendFailure(Messages.get("spawnmob.limited", limit));
                amount = limit;
            }
            ServerLevel level;
            Vec3 pos;
            if (at != null) {
                level = at.serverLevel();
                pos = at.position();
            } else {
                ServerPlayer me = self(ctx);
                level = me.serverLevel();
                BlockHitResult hit = lookingAt(me, 100);
                pos = Vec3.atBottomCenterOf(hit.getBlockPos().relative(hit.getDirection()));
            }
            int n = 0;
            for (int i = 0; i < amount; i++) {
                Entity e = type.create(level);
                if (e == null) break;
                e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0);
                if (e instanceof Mob mob) {
                    mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(pos)), MobSpawnType.COMMAND, null);
                }
                if (level.addFreshEntity(e)) n++;
            }
            reply(ctx, Messages.get("spawnmob.done", n, type.getDescription()));
            return n > 0 ? 1 : 0;
        }
    }

    /** /spawner <entity>: changes the mob a spawner you're looking at spawns. */
    static final class Spawner extends EssCommand {
        Spawner() {
            super("spawner", Perms.OP, "espawner", "changems", "echangems", "mobspawner", "emobspawner");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("entity", ResourceArgument.resource(buildContext(), Registries.ENTITY_TYPE))
                    .suggests(SuggestionProviders.SUMMONABLE_ENTITIES).executes(exec(ctx -> {
                        ServerPlayer me = self(ctx);
                        BlockPos pos = lookingAt(me, 8).getBlockPos();
                        if (!(me.level().getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) return fail(ctx, Messages.get("spawner.lookAt"));
                        EntityType<?> type = ResourceArgument.getSummonableEntityType(ctx, "entity").value();
                        String path = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
                        if (!has(ctx.getSource(), path, Perms.OP)) return fail(ctx, Messages.get("spawnmob.noPermission", type.getDescription()));
                        spawner.setEntityId(type, me.getRandom());
                        spawner.setChanged();
                        me.level().sendBlockUpdated(pos, me.level().getBlockState(pos), me.level().getBlockState(pos), 3);
                        reply(ctx, Messages.get("spawner.set", type.getDescription()));
                        return 1;
                    })));
        }
    }

    // ---------------------------------------------------------------- tree, bigtree

    /** /tree [type] and /bigtree [type]: grows a tree on the block you look at. */
    static final class Tree extends EssCommand {
        private final Map<String, ResourceKey<ConfiguredFeature<?, ?>>> types = new LinkedHashMap<>();

        Tree(boolean big) {
            super(big ? "bigtree" : "tree", Perms.OP, big ? new String[] {"ebigtree", "largetree", "elargetree"}
                    : new String[] {"etree"});
            if (big) {
                types.put("tree", TreeFeatures.FANCY_OAK);
                types.put("redwood", TreeFeatures.MEGA_SPRUCE);
                types.put("spruce", TreeFeatures.MEGA_SPRUCE);
                types.put("pine", TreeFeatures.MEGA_PINE);
                types.put("jungle", TreeFeatures.MEGA_JUNGLE_TREE);
                types.put("darkoak", TreeFeatures.DARK_OAK);
                types.put("mangrove", TreeFeatures.TALL_MANGROVE);
                types.put("birch", TreeFeatures.SUPER_BIRCH_BEES);
            } else {
                types.put("tree", TreeFeatures.OAK);
                types.put("birch", TreeFeatures.BIRCH);
                types.put("redwood", TreeFeatures.SPRUCE);
                types.put("spruce", TreeFeatures.SPRUCE);
                types.put("pine", TreeFeatures.PINE);
                types.put("jungle", TreeFeatures.JUNGLE_TREE_NO_VINE);
                types.put("acacia", TreeFeatures.ACACIA);
                types.put("darkoak", TreeFeatures.DARK_OAK);
                types.put("swamp", TreeFeatures.SWAMP_OAK);
                types.put("cherry", TreeFeatures.CHERRY);
                types.put("mangrove", TreeFeatures.MANGROVE);
                types.put("azalea", TreeFeatures.AZALEA_TREE);
                types.put("redmushroom", TreeFeatures.HUGE_RED_MUSHROOM);
                types.put("brownmushroom", TreeFeatures.HUGE_BROWN_MUSHROOM);
            }
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> grow(ctx, "tree")))
                    .then(Commands.argument("type", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(types.keySet(), b))
                            .executes(exec(ctx -> grow(ctx, str(ctx, "type").toLowerCase(Locale.ROOT)))));
        }

        private int grow(CommandContext<CommandSourceStack> ctx, String type) throws CommandSyntaxException {
            ResourceKey<ConfiguredFeature<?, ?>> key = types.get(type);
            if (key == null) return fail(ctx, Messages.get("tree.unknown", type, String.join(", ", types.keySet())));
            ServerPlayer me = self(ctx);
            ServerLevel level = me.serverLevel();
            BlockPos ground = lookingAt(me, 100).getBlockPos();
            var feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(key);
            if (feature.isEmpty() || !feature.get().value().place(level, level.getChunkSource().getGenerator(), level.random, ground.above())) {
                return fail(ctx, Messages.get("tree.failed"));
            }
            reply(ctx, Messages.get("tree.grown"));
            return 1;
        }
    }

    // ---------------------------------------------------------------- gc

    /** /gc: TPS, memory, and loaded chunks and entities per dimension. */
    static final class Gc extends EssCommand {
        Gc() {
            super("gc", Perms.OP, "egc", "lag", "elag", "mem", "emem", "memory", "ememory", "uptime", "euptime", "tps", "etps");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();
                double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
                double tps = Math.min(server.tickRateManager().tickrate(), 1000.0 / Math.max(mspt, 0.001));
                Runtime rt = Runtime.getRuntime();
                long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
                reply(ctx, Messages.get("gc.uptime", Durations.format(uptime)));
                reply(ctx, Messages.get("gc.tps", String.format(Locale.ROOT, "%.1f", tps), String.format(Locale.ROOT, "%.1f", mspt)));
                reply(ctx, Messages.get("gc.memory", rt.maxMemory() / 1048576, rt.totalMemory() / 1048576, rt.freeMemory() / 1048576));
                for (ServerLevel level : server.getAllLevels()) {
                    int entities = 0;
                    for (Entity ignored : level.getAllEntities()) entities++;
                    reply(ctx, Messages.get("gc.world", level.dimension().location().toString(),
                            level.getChunkSource().getLoadedChunksCount(), entities));
                }
                return 1;
            }));
        }
    }

    // ---------------------------------------------------------------- backup

    /** /backup: saves the world, pauses saving, runs world.backupCommand, then turns saving back on. */
    static final class Backup extends EssCommand {
        private static final AtomicBoolean running = new AtomicBoolean();

        Backup() {
            super("backup", Perms.ADMIN, "ebackup");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                String command = EssConfig.BACKUP_COMMAND.get().trim();
                if (command.isEmpty()) return fail(ctx, Messages.get("backup.notSet"));
                if (!running.compareAndSet(false, true)) return fail(ctx, Messages.get("backup.running"));
                MinecraftServer server = ctx.getSource().getServer();
                server.saveEverything(true, true, true);
                List<ServerLevel> paused = new ArrayList<>();
                for (ServerLevel level : server.getAllLevels()) {
                    if (!level.noSave) {
                        level.noSave = true;
                        paused.add(level);
                    }
                }
                CommandSourceStack src = ctx.getSource();
                reply(ctx, Messages.get("backup.started"));
                Thread t = new Thread(() -> {
                    int code;
                    try {
                        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                        ProcessBuilder pb = windows ? new ProcessBuilder("cmd", "/c", command) : new ProcessBuilder("sh", "-c", command);
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = r.readLine()) != null) CuratoriumEssentials.LOGGER.info("[backup] {}", line);
                        }
                        code = proc.waitFor();
                    } catch (Exception e) {
                        CuratoriumEssentials.LOGGER.error("Backup command failed", e);
                        code = -1;
                    }
                    int exit = code;
                    server.execute(() -> {
                        for (ServerLevel level : paused) level.noSave = false;
                        running.set(false);
                        src.sendSuccess(() -> exit == 0 ? Messages.get("backup.done") : Messages.get("backup.failed", exit), true);
                    });
                }, "Curatorium Essentials backup");
                t.setDaemon(true);
                t.start();
                return 1;
            }));
        }
    }

    private WorldCommands() {}
}
