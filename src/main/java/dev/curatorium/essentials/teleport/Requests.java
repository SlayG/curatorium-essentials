package dev.curatorium.essentials.teleport;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.chat.Chat;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.Location;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Teleport requests: /tpa, /tpahere, /tpaall, /tpaccept, /tpdeny, /tpacancel, /tpauto. */
public final class Requests {
    /** {@code here}: the receiver is asked to come to the sender (/tpahere). */
    record Request(UUID from, UUID to, boolean here, long time) {
        boolean expired(long now) {
            return now - time > EssConfig.TPA_TIMEOUT.get() * 1000L;
        }
    }

    /** receiver -> sender -> request (newest last) */
    private static final Map<UUID, LinkedHashMap<UUID, Request>> open = new ConcurrentHashMap<>();

    public static void register() {
        CommandRegistry.add(new Tpa("tpa", false, "etpa", "call", "ecall", "tpask", "etpask"));
        CommandRegistry.add(new Tpa("tpahere", true, "etpahere"));
        CommandRegistry.add(new TpaAll());
        CommandRegistry.add(new Answer("tpaccept", true, "etpaccept", "tpyes", "etpyes"));
        CommandRegistry.add(new Answer("tpdeny", false, "etpdeny", "tpno", "etpno"));
        CommandRegistry.add(new TpaCancel());
        CommandRegistry.add(new TpAuto());
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            UUID id = e.getEntity().getUUID();
            open.remove(id);
            open.values().forEach(m -> m.remove(id));
        });
    }

    private static Map<UUID, Request> to(UUID receiver) {
        LinkedHashMap<UUID, Request> m = open.computeIfAbsent(receiver, k -> new LinkedHashMap<>());
        long now = System.currentTimeMillis();
        m.values().removeIf(r -> r.expired(now));
        return m;
    }

    private static MutableComponent button(String key, String command) {
        return Messages.get(key).withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    /** Sends a request. Returns 1 if sent (or silently dropped because the receiver ignores the sender). */
    static int send(CommandSourceStack src, ServerPlayer from, ServerPlayer target, boolean here) {
        if (target == from) {
            src.sendFailure(Messages.get("teleport.self"));
            return 0;
        }
        if (!Teleports.accepts(target, from)) {
            src.sendFailure(Messages.get("teleport.targetToggled", target.getDisplayName()));
            return 0;
        }
        if (Chat.ignores(target, from.getUUID())) {
            src.sendSuccess(() -> Messages.get("tpa.sent", target.getDisplayName()), false);
            return 1;
        }
        Map<UUID, Request> m = to(target.getUUID());
        m.remove(from.getUUID());
        m.put(from.getUUID(), new Request(from.getUUID(), target.getUUID(), here, System.currentTimeMillis()));
        if (UserStore.get(target).tpAuto && !here) {
            target.sendSystemMessage(Messages.get("tpa.autoAccepted", from.getDisplayName()));
            accept(target, from.getUUID());
            return 1;
        }
        String name = from.getGameProfile().getName();
        target.sendSystemMessage(Messages.get(here ? "tpa.receivedHere" : "tpa.received", from.getDisplayName()));
        target.sendSystemMessage(Component.empty().append(button("tpa.acceptButton", "/tpaccept " + name))
                .append(Component.literal(" ")).append(button("tpa.denyButton", "/tpdeny " + name))
                .append(Messages.get("tpa.expires", EssConfig.TPA_TIMEOUT.get())));
        src.sendSuccess(() -> Messages.get("tpa.sent", target.getDisplayName()), false);
        return 1;
    }

    /** The receiver accepts the request from {@code fromId}. */
    static int accept(ServerPlayer receiver, UUID fromId) {
        Request r = to(receiver.getUUID()).remove(fromId);
        if (r == null) {
            receiver.sendSystemMessage(Messages.get("tpa.none"));
            return 0;
        }
        MinecraftServer server = receiver.server;
        ServerPlayer from = server.getPlayerList().getPlayer(fromId);
        if (from == null) {
            receiver.sendSystemMessage(Messages.get("tpa.senderOffline"));
            return 0;
        }
        ServerPlayer mover = r.here() ? receiver : from;
        ServerPlayer stay = r.here() ? from : receiver;
        from.sendSystemMessage(Messages.get("tpa.acceptedBy", receiver.getDisplayName()));
        receiver.sendSystemMessage(Messages.get("tpa.accepted", from.getDisplayName()));
        // The destination is where the other player stands now, when the request is accepted.
        Teleports.go(mover, Location.of(stay), true, Messages.get("tpa.arrived", stay.getDisplayName()));
        return 1;
    }

    // ---------------------------------------------------------------- commands

    static final class Tpa extends EssCommand {
        private final boolean here;

        Tpa(String name, boolean here, String... aliases) {
            super(name, Perms.OP, aliases);
            this.here = here;
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> send(ctx.getSource(), self(ctx), online(ctx, "player"), here))));
        }
    }

    /** /tpaall: asks everyone online to come to you. */
    static final class TpaAll extends EssCommand {
        TpaAll() {
            super("tpaall", Perms.OP, "etpaall");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                int n = 0;
                for (ServerPlayer p : List.copyOf(me.server.getPlayerList().getPlayers())) {
                    if (p == me || !Teleports.accepts(p, me)) continue;
                    Map<UUID, Request> m = to(p.getUUID());
                    m.remove(me.getUUID());
                    m.put(me.getUUID(), new Request(me.getUUID(), p.getUUID(), true, System.currentTimeMillis()));
                    String name = me.getGameProfile().getName();
                    p.sendSystemMessage(Messages.get("tpa.receivedHere", me.getDisplayName()));
                    p.sendSystemMessage(Component.empty().append(button("tpa.acceptButton", "/tpaccept " + name))
                            .append(Component.literal(" ")).append(button("tpa.denyButton", "/tpdeny " + name)));
                    n++;
                }
                reply(ctx, Messages.get("tpaall.sent", n));
                return 1;
            }));
        }
    }

    /** /tpaccept [player|*], /tpdeny [player|*]: without a name, the newest request. */
    static final class Answer extends EssCommand {
        private final boolean accept;

        Answer(String name, boolean accept, String... aliases) {
            super(name, Perms.OP, aliases);
            this.accept = accept;
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> answer(ctx, null)))
                    .then(playerArg("player").executes(exec(ctx -> answer(ctx, str(ctx, "player")))));
        }

        private int answer(CommandContext<CommandSourceStack> ctx, String who) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            Map<UUID, Request> m = to(me.getUUID());
            if (m.isEmpty()) return fail(ctx, Messages.get("tpa.none"));
            List<UUID> senders = new ArrayList<>();
            if (who == null) {
                senders.add(new ArrayList<>(m.keySet()).get(m.size() - 1));
            } else if (who.equals("*")) {
                senders.addAll(m.keySet());
            } else {
                ServerPlayer from = online(ctx, "player");
                if (!m.containsKey(from.getUUID())) return fail(ctx, Messages.get("tpa.noneFrom", from.getDisplayName()));
                senders.add(from.getUUID());
            }
            int n = 0;
            for (UUID id : senders) {
                if (accept) {
                    n += Requests.accept(me, id);
                } else {
                    m.remove(id);
                    ServerPlayer from = me.server.getPlayerList().getPlayer(id);
                    if (from != null) {
                        from.sendSystemMessage(Messages.get("tpa.deniedBy", me.getDisplayName()));
                        reply(ctx, Messages.get("tpa.denied", from.getDisplayName()));
                    }
                    n++;
                }
            }
            return n;
        }
    }

    /** /tpacancel [player]: withdraws your requests. */
    static final class TpaCancel extends EssCommand {
        TpaCancel() {
            super("tpacancel", Perms.OP, "etpacancel");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> cancel(ctx, null)))
                    .then(playerArg("player").executes(exec(ctx -> cancel(ctx, online(ctx, "player")))));
        }

        private int cancel(CommandContext<CommandSourceStack> ctx, ServerPlayer only) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            int n = 0;
            for (var entry : open.entrySet()) {
                if (only != null && !entry.getKey().equals(only.getUUID())) continue;
                if (entry.getValue().remove(me.getUUID()) != null) n++;
            }
            if (n == 0) return fail(ctx, Messages.get("tpacancel.none"));
            reply(ctx, Messages.get("tpacancel.done", n));
            return 1;
        }
    }

    static final class TpAuto extends EssCommand {
        TpAuto() {
            super("tpauto", Perms.OP, "etpauto");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                UserData d = UserStore.get(p);
                d.tpAuto = !d.tpAuto;
                d.markDirty();
                Component state = Messages.get(d.tpAuto ? "toggle.on" : "toggle.off");
                p.sendSystemMessage(Messages.get("tpauto.state", state));
                tellOther(ctx, p, Messages.get("toggle.other", Messages.get("tpauto.state", state), p.getDisplayName()));
                return 1;
            });
        }
    }

    private Requests() {}
}
