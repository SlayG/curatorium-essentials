package dev.curatorium.essentials.player;

import com.mojang.brigadier.ParseResults;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Away from keyboard: {@code /afk}, auto-AFK after {@code afk.autoAfkSeconds} without moving,
 * looking around, chatting or using commands, and the optional AFK kick.
 */
public final class Afk {
    private static final Set<String> AFK_LABELS = Set.of("afk", "eafk", "away", "eaway");

    private static final class State {
        boolean afk;
        long since;
        String message;
        long lastActive = System.currentTimeMillis();
        double x, y, z;
        float yRot, xRot;
    }

    private static final Map<UUID, State> states = new ConcurrentHashMap<>();
    private static int ticks;

    public static void registerEvents(IEventBus bus) {
        bus.addListener(ServerTickEvent.Post.class, e -> {
            if (++ticks % 20 == 0) tick(e.getServer());
        });
        bus.addListener(EventPriority.HIGHEST, ServerChatEvent.class, e -> activity(e.getPlayer()));
        bus.addListener(CommandEvent.class, e -> {
            ParseResults<CommandSourceStack> parse = e.getParseResults();
            ServerPlayer p = parse.getContext().getSource().getPlayer();
            if (p == null) return;
            String input = parse.getReader().getString();
            if (input.startsWith("/")) input = input.substring(1);
            int space = input.indexOf(' ');
            String label = (space < 0 ? input : input.substring(0, space)).toLowerCase();
            if (!AFK_LABELS.contains(label)) activity(p);
        });
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) state(p).lastActive = System.currentTimeMillis();
        });
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> states.remove(e.getEntity().getUUID()));
    }

    public static boolean isAfk(ServerPlayer player) {
        State s = states.get(player.getUUID());
        return s != null && s.afk;
    }

    /** The player's AFK message, or null. */
    public static String message(ServerPlayer player) {
        State s = states.get(player.getUUID());
        return s != null && s.afk ? s.message : null;
    }

    /** Millis since the player went AFK, 0 if they aren't. */
    public static long afkFor(ServerPlayer player) {
        State s = states.get(player.getUUID());
        return s != null && s.afk ? System.currentTimeMillis() - s.since : 0;
    }

    public static void activity(ServerPlayer player) {
        State s = state(player);
        s.lastActive = System.currentTimeMillis();
        if (s.afk) setAfk(player, false, null);
    }

    public static void setAfk(ServerPlayer player, boolean afk, String message) {
        State s = state(player);
        if (s.afk == afk) return;
        s.afk = afk;
        s.since = System.currentTimeMillis();
        s.message = afk ? message : null;
        if (!afk) s.lastActive = s.since;
        remember(s, player);
        Names.refresh(player);
        Component name = player.getDisplayName();
        Component note = afk
                ? (message == null || message.isBlank() ? Messages.get("afk.now", name) : Messages.get("afk.nowMessage", name, message))
                : Messages.get("afk.noLonger", name);
        if (EssConfig.AFK_BROADCAST.get()) {
            player.server.getPlayerList().broadcastSystemMessage(note, false);
        } else {
            player.sendSystemMessage(note);
        }
    }

    private static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        long auto = EssConfig.AUTO_AFK.get() * 1000L;
        long kick = EssConfig.AFK_KICK.get() * 1000L;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            State s = state(p);
            if (moved(s, p)) {
                remember(s, p);
                activity(p);
                continue;
            }
            long idle = now - s.lastActive;
            if (!s.afk && auto > 0 && idle >= auto && Perms.has(p, "essentials.afk.auto", Perms.ALL)) {
                setAfk(p, true, null);
            }
            if (kick > 0 && idle >= kick && !Perms.has(p, "essentials.afk.kickexempt", Perms.OP)) {
                p.connection.disconnect(Messages.get("afk.kick"));
            }
        }
    }

    private static boolean moved(State s, ServerPlayer p) {
        return Math.abs(p.getX() - s.x) > 0.1 || Math.abs(p.getY() - s.y) > 0.1 || Math.abs(p.getZ() - s.z) > 0.1
                || Math.abs(p.getYRot() - s.yRot) > 1 || Math.abs(p.getXRot() - s.xRot) > 1;
    }

    private static void remember(State s, ServerPlayer p) {
        s.x = p.getX();
        s.y = p.getY();
        s.z = p.getZ();
        s.yRot = p.getYRot();
        s.xRot = p.getXRot();
    }

    private static State state(ServerPlayer player) {
        return states.computeIfAbsent(player.getUUID(), id -> {
            State s = new State();
            remember(s, player);
            return s;
        });
    }

    private Afk() {}
}
