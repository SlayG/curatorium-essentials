package dev.curatorium.essentials.teleport;

import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.JsonFile;
import dev.curatorium.essentials.data.Location;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Tasks;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * The Spawn module: /setspawn [group], /spawn [player], one spawn per LuckPerms group (the
 * player's primary group) with "default" for the rest, the first-join spawn, spawning on every
 * join, and respawning at /spawn without a bed. Without any spawn set, the world spawn is used.
 */
public final class Spawns {
    private static final String FILE = "spawns.json";
    public static final String DEFAULT = "default";
    private static Map<String, Location> spawns = new TreeMap<>();

    public static void register() {
        CommandRegistry.add(new SetSpawn());
        CommandRegistry.add(new SpawnCommand());
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(ServerStartedEvent.class, e -> {
            Map<String, Location> loaded = JsonFile.read(FILE, new TypeToken<TreeMap<String, Location>>() {}.getType(), () -> new TreeMap<String, Location>());
            spawns = new TreeMap<>(loaded);
        });
        // LOWEST: after the player store recorded the login (so a first join can be told apart).
        bus.addListener(EventPriority.LOWEST, PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            UserData d = UserStore.get(p);
            boolean first = d.firstJoin == d.lastLogin;
            Location to = null;
            String newbies = EssConfig.NEW_PLAYER_SPAWN.get().trim().toLowerCase(Locale.ROOT);
            if (first && !newbies.equals("none") && !newbies.isEmpty()) {
                to = spawns.getOrDefault(newbies, spawns.get(DEFAULT));
            } else if (EssConfig.SPAWN_ON_JOIN.get() && d.jail == null) {
                to = spawnFor(p);
            }
            if (to == null) return;
            Location dest = to;
            // A tick later, so the client has finished joining.
            Tasks.later(1, () -> {
                if (!p.hasDisconnected()) Teleports.go(p, dest, false, null);
            });
        });
        bus.addListener(PlayerRespawnPositionEvent.class, e -> {
            if (!EssConfig.RESPAWN_AT_SPAWN.get() || !(e.getEntity() instanceof ServerPlayer p) || spawns.isEmpty()) return;
            DimensionTransition original = e.getDimensionTransition();
            boolean noBed = p.getRespawnPosition() == null || original.missingRespawnBlock();
            if (!noBed || UserStore.get(p).jail != null) return;
            Location spawn = spawnFor(p);
            ServerLevel level = spawn.level(p.server);
            if (level == null) return;
            Location safe = Safety.find(level, spawn);
            Location l = safe != null ? safe : spawn;
            e.setDimensionTransition(new DimensionTransition(level, new Vec3(l.x(), l.y(), l.z()), Vec3.ZERO, l.yaw(), l.pitch(),
                    original.missingRespawnBlock(), DimensionTransition.DO_NOTHING));
        });
    }

    private static void save() {
        JsonFile.write(FILE, spawns);
    }

    /** The player's spawn: their group's, else "default", else the world spawn. */
    public static Location spawnFor(ServerPlayer p) {
        String group = Perms.meta(p).group();
        Location l = group == null ? null : spawns.get(group.toLowerCase(Locale.ROOT));
        if (l == null) l = spawns.get(DEFAULT);
        return l != null ? l : worldSpawn(p.server);
    }

    static Location worldSpawn(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos s = level.getSharedSpawnPos();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.getX(), s.getZ());
        return new Location(level.dimension().location().toString(), s.getX() + 0.5, Math.max(y, s.getY()), s.getZ() + 0.5,
                level.getSharedSpawnAngle(), 0);
    }

    static final class SetSpawn extends EssCommand {
        SetSpawn() {
            super("setspawn", Perms.OP, "esetspawn");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> set(ctx.getSource(), DEFAULT)))
                    .then(Commands.argument("group", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(spawns.keySet(), b))
                            .executes(exec(ctx -> set(ctx.getSource(), str(ctx, "group").toLowerCase(Locale.ROOT)))));
        }

        private int set(CommandSourceStack src, String group) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer me = src.getPlayerOrException();
            Location here = Location.of(me);
            spawns.put(group, here);
            save();
            if (group.equals(DEFAULT) && me.serverLevel() == me.server.overworld()) {
                // Keep vanilla's world spawn (compasses, new chunks loaded around it) in step.
                me.serverLevel().setDefaultSpawnPos(me.blockPosition(), me.getYRot());
            }
            src.sendSuccess(() -> Messages.get("spawn.set", group), false);
            return 1;
        }
    }

    /** /spawn [player] */
    static final class SpawnCommand extends EssCommand {
        SpawnCommand() {
            super("spawn", Perms.OP, "espawn");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                boolean other = ctx.getSource().getPlayer() != p;
                // Sending someone else is a staff action: no warmup for them to wait through.
                if (!Teleports.go(p, spawnFor(p), !other, Messages.get("spawn.arrived"))) return 0;
                tellOther(ctx, p, Messages.get("spawn.sent", p.getDisplayName()));
                return 1;
            });
        }
    }

    private Spawns() {}
}
