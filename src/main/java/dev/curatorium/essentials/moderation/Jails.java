package dev.curatorium.essentials.moderation;

import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.JsonFile;
import dev.curatorium.essentials.data.Location;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Durations;
import dev.curatorium.essentials.util.Notify;
import dev.curatorium.essentials.util.PlayerLookup;
import dev.curatorium.essentials.util.Tasks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Jails: named points (in {@code <world>/curatorium_essentials/jails.json}). A jailed player is
 * kept within {@code jail.radius} of the point, can't break, place or use anything, and can only
 * run {@code jail.allowedCommands}. Sentences run in real time, or only while online with
 * {@code jail.countOnlineTimeOnly}.
 */
public final class Jails {
    private static final String FILE = "jails.json";
    private static final int CHECK_TICKS = 10;
    private static Map<String, Location> jails = new TreeMap<>();
    private static int ticks;

    static final SuggestionProvider<CommandSourceStack> JAIL_NAMES = (ctx, b) -> SharedSuggestionProvider.suggest(jails.keySet(), b);

    public static void register() {
        CommandRegistry.add(new SetJail());
        CommandRegistry.add(new DelJail());
        CommandRegistry.add(new ListJails());
        CommandRegistry.add(new ToggleJail());
        CommandRegistry.add(new JailedPlayers());
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(ServerStartedEvent.class, e -> {
            Map<String, Location> loaded = JsonFile.read(FILE, new TypeToken<TreeMap<String, Location>>() {}.getType(),
                    () -> new TreeMap<String, Location>());
            jails = new TreeMap<>(loaded);
        });
        bus.addListener(ServerTickEvent.Post.class, e -> {
            if (++ticks < CHECK_TICKS) return;
            ticks = 0;
            for (ServerPlayer p : new ArrayList<>(e.getServer().getPlayerList().getPlayers())) check(p);
        });
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            UserData d = UserStore.get(p);
            if (d.jail == null && d.preJailLocation != null) {
                returnFromJail(p, d);
                p.sendSystemMessage(Messages.get("jail.released"));
            } else if (d.jail != null) {
                Tasks.later(1, () -> sendToJail(p));
            }
        });
        bus.addListener(PlayerEvent.PlayerRespawnEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p && isJailed(p)) Tasks.later(1, () -> sendToJail(p));
        });
        bus.addListener(EventPriority.HIGH, CommandEvent.class, e -> {
            ServerPlayer p = e.getParseResults().getContext().getSource().getPlayer();
            if (p == null || e.isCanceled() || !isJailed(p)) return;
            String label = Mutes.Commands.label(e);
            if (label == null) return;
            EssCommand ess = CommandRegistry.byLabel(label);
            if (EssConfig.jailAllows(label) || (ess != null && EssConfig.jailAllows(ess.name()))) return;
            e.setCanceled(true);
            p.sendSystemMessage(Messages.get("jail.noCommands"));
        });
        bus.addListener(BlockEvent.BreakEvent.class, e -> {
            if (e.getPlayer() instanceof ServerPlayer p) deny(p, e, true);
        });
        bus.addListener(BlockEvent.EntityPlaceEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) deny(p, e, true);
        });
        bus.addListener(PlayerInteractEvent.RightClickBlock.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) deny(p, e, false);
        });
        bus.addListener(PlayerInteractEvent.RightClickItem.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) deny(p, e, false);
        });
        bus.addListener(PlayerInteractEvent.EntityInteract.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) deny(p, e, false);
        });
        bus.addListener(AttackEntityEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) deny(p, e, false);
        });
    }

    private static void deny(ServerPlayer p, ICancellableEvent e, boolean tell) {
        if (!isJailed(p)) return;
        e.setCanceled(true);
        if (tell) p.sendSystemMessage(Messages.get("jail.cantDoThat"));
    }

    public static boolean isJailed(ServerPlayer p) {
        return UserStore.get(p).jail != null;
    }

    private static void save() {
        JsonFile.write(FILE, jails);
    }

    private static void check(ServerPlayer p) {
        UserData d = UserStore.get(p);
        if (d.jail == null) return;
        long now = System.currentTimeMillis();
        if (d.jailLeft > 0) {
            d.jailLeft -= CHECK_TICKS * 50L;
            d.markDirty();
            if (d.jailLeft <= 0) {
                release(p, d, true);
                return;
            }
        } else if (d.jailUntil > 0 && d.jailUntil <= now) {
            release(p, d, true);
            return;
        }
        Location jail = jails.get(d.jail);
        if (jail == null) {
            release(p, d, false);
            return;
        }
        int radius = EssConfig.JAIL_RADIUS.get();
        double max = Math.max(1, radius);
        if (!jail.sameDimension(p) || jail.distanceSqr(p) > max * max) sendToJail(p);
    }

    private static void sendToJail(ServerPlayer p) {
        UserData d = UserStore.get(p);
        Location jail = d.jail == null ? null : jails.get(d.jail);
        if (jail != null) jail.teleport(p);
    }

    /** Lets a player out: clears their sentence and returns them to where they were jailed from. */
    static void release(ServerPlayer p, UserData d, boolean expired) {
        d.jail = null;
        d.jailUntil = 0;
        d.jailLeft = 0;
        d.jailReason = null;
        d.markDirty();
        // Offline: preJailLocation stays, and they're sent back at their next login.
        if (p != null) {
            returnFromJail(p, d);
            p.sendSystemMessage(Messages.get(expired ? "jail.served" : "jail.released"));
        }
    }

    private static void returnFromJail(ServerPlayer p, UserData d) {
        Location back = d.preJailLocation;
        d.preJailLocation = null;
        d.markDirty();
        if (back != null) back.teleport(p);
    }

    static Component timeLeft(UserData d) {
        if (d.jailLeft > 0) return Messages.get("jail.timeOnline", Durations.format(d.jailLeft));
        if (d.jailUntil > 0) return Component.literal(Durations.format(d.jailUntil - System.currentTimeMillis()));
        return Messages.get("mute.forever");
    }

    // ---------------------------------------------------------------- commands

    static final class SetJail extends EssCommand {
        SetJail() {
            super("setjail", Perms.OP, "esetjail", "createjail", "ecreatejail");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.word()).executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                String name = str(ctx, "name").toLowerCase(Locale.ROOT);
                jails.put(name, Location.of(me));
                save();
                reply(ctx, Messages.get("jail.set", name));
                return 1;
            })));
        }
    }

    static final class DelJail extends EssCommand {
        DelJail() {
            super("deljail", Perms.OP, "edeljail", "remjail", "eremjail", "rmjail", "ermjail");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.word()).suggests(JAIL_NAMES).executes(exec(ctx -> {
                String name = str(ctx, "name").toLowerCase(Locale.ROOT);
                if (jails.remove(name) == null) return fail(ctx, Messages.get("jail.unknown", name));
                save();
                reply(ctx, Messages.get("jail.deleted", name));
                return 1;
            })));
        }
    }

    static final class ListJails extends EssCommand {
        ListJails() {
            super("jails", Perms.OP, "ejails");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                if (jails.isEmpty()) reply(ctx, Messages.get("jail.none"));
                else reply(ctx, Messages.get("jail.list", String.join(", ", jails.keySet())));
                return 1;
            }));
        }
    }

    /** /togglejail <player> [jail] [time] [reason]: jails, or with only a player, releases a jailed player. */
    static final class ToggleJail extends EssCommand {
        ToggleJail() {
            super("togglejail", Perms.OP, "etogglejail", "jail", "ejail", "tjail", "etjail", "unjail", "eunjail");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> toggle(ctx, null, null)))
                    .then(Commands.argument("jail", StringArgumentType.word()).suggests(JAIL_NAMES)
                            .executes(exec(ctx -> toggle(ctx, str(ctx, "jail"), null)))
                            .then(textArg("time and reason").executes(exec(ctx -> toggle(ctx, str(ctx, "jail"), str(ctx, "time and reason")))))));
        }

        private int toggle(CommandContext<CommandSourceStack> ctx, String jailName, String rest) throws CommandSyntaxException {
            CommandSourceStack src = ctx.getSource();
            MinecraftServer server = src.getServer();
            PlayerLookup.Target target = known(ctx, "player");
            UserData d = UserStore.get(target.uuid());
            String by = Bans.sourceName(src);
            String label = ctx.getNodes().get(0).getNode().getName();
            if (d.jail != null && (jailName == null || label.endsWith("unjail"))) {
                release(target.online(), d, false);
                Notify.staff(server, "essentials.jail.notify", Messages.get("jail.notifyReleased", by, target.name()));
                return 1;
            }
            if (label.endsWith("unjail")) return fail(ctx, Messages.get("jail.notJailed", target.name()));
            if (jailName == null) {
                if (jails.size() != 1) return fail(ctx, Messages.get("jail.usage"));
                jailName = jails.keySet().iterator().next();
            }
            String name = jailName.toLowerCase(Locale.ROOT);
            Location jail = jails.get(name);
            if (jail == null) return fail(ctx, Messages.get("jail.unknown", name));
            if (!target.isOnline() && !Perms.has(src, "essentials.togglejail.offline", Perms.OP)) {
                return fail(ctx, Messages.get("jail.offlineDenied", target.name()));
            }
            if (target.isOnline() && target.online() != src.getPlayer()
                    && Perms.has(target.online(), "essentials.jail.exempt", Perms.ADMIN)) {
                return fail(ctx, Messages.get("jail.exempt", target.name()));
            }
            Bans.TimeAndReason tr = Bans.TimeAndReason.parse(rest, false);
            boolean firstTime = d.jail == null;
            d.jail = name;
            d.jailUntil = 0;
            d.jailLeft = 0;
            if (tr.millis().isPresent()) {
                if (EssConfig.JAIL_ONLINE_TIME.get()) d.jailLeft = tr.millis().getAsLong();
                else d.jailUntil = System.currentTimeMillis() + tr.millis().getAsLong();
            }
            d.jailReason = tr.reason() == null || tr.reason().isBlank() ? null : tr.reason().trim();
            if (firstTime) d.preJailLocation = target.isOnline() ? Location.of(target.online()) : d.logoutLocation;
            d.markDirty();
            Component time = timeLeft(d);
            if (target.isOnline()) {
                jail.teleport(target.online());
                target.online().sendSystemMessage(Messages.get("jail.youAreJailed", time,
                        d.jailReason == null ? Messages.raw("mute.noReason") : d.jailReason));
            }
            Notify.staff(server, "essentials.jail.notify", Messages.get("jail.notify", by, target.name(), name, time));
            return 1;
        }
    }

    static final class JailedPlayers extends EssCommand {
        JailedPlayers() {
            super("jailedplayers", Perms.OP, "ejailedplayers");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                int found = 0;
                for (UUID id : UserStore.allKnown()) {
                    UserData d = UserStore.get(id);
                    if (d.jail == null) continue;
                    found++;
                    reply(ctx, Messages.get("jail.entry", d.lastName, d.jail, timeLeft(d)));
                }
                if (found == 0) reply(ctx, Messages.get("jail.nobody"));
                return 1;
            }));
        }
    }

    private Jails() {}
}
