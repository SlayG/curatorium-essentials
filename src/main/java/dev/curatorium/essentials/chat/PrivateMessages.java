package dev.curatorium.essentials.chat;

import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.player.Afk;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.Text;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /msg} and {@code /r}. The console can send and receive (as "Console"). Social spies
 * see every message they aren't part of.
 */
public final class PrivateMessages {
    /** Stands for the console in reply tracking. */
    public static final UUID CONSOLE = new UUID(0, 0);

    /** receiver -> who last messaged them */
    private static final Map<UUID, UUID> lastFrom = new ConcurrentHashMap<>();
    /** sender -> who they last messaged */
    private static final Map<UUID, UUID> lastTo = new ConcurrentHashMap<>();

    public static UUID idOf(CommandSourceStack source) {
        ServerPlayer p = source.getPlayer();
        return p == null ? CONSOLE : p.getUUID();
    }

    /** Who {@code /r} goes to for this source, or null. */
    public static UUID replyTarget(CommandSourceStack source) {
        UUID me = idOf(source);
        ServerPlayer p = source.getPlayer();
        boolean toSent = p != null && UserStore.get(p).replyToSent;
        UUID target = toSent ? lastTo.get(me) : lastFrom.get(me);
        return target != null ? target : (toSent ? lastFrom.get(me) : lastTo.get(me));
    }

    /**
     * Sends a private message. {@code to} is null for the console. Returns 1 if sent (or
     * silently dropped because the receiver ignores the sender), 0 if refused.
     */
    public static int send(CommandSourceStack source, ServerPlayer to, String raw) {
        MinecraftServer server = source.getServer();
        ServerPlayer from = source.getPlayer();
        if (from != null && from == to) {
            source.sendFailure(Messages.get("msg.self"));
            return 0;
        }
        Component fromName = from == null ? Messages.get("console.name") : from.getDisplayName();
        Component toName = to == null ? Messages.get("console.name") : to.getDisplayName();

        if (to != null && from != null) {
            UserData target = UserStore.get(to);
            if (target.msgDisabled && !Perms.has(from, "essentials.msgtoggle.bypass", Perms.OP)) {
                source.sendFailure(Messages.get("msg.disabledTarget", toName));
                return 0;
            }
        }

        Component text;
        if (from == null) {
            text = Text.user(raw, true, true, true, true);
        } else {
            Perms.TextPerms tp = Perms.textPerms(from, "essentials.msg");
            text = Text.user(raw, tp.color(), tp.format(), tp.magic(), tp.links());
        }

        UUID fromId = idOf(source);
        UUID toId = to == null ? CONSOLE : to.getUUID();
        lastTo.put(fromId, toId);

        source.sendSystemMessage(Messages.get("msg.sent", toName, text));
        boolean ignored = to != null && from != null && Chat.ignores(to, from.getUUID());
        if (!ignored) {
            lastFrom.put(toId, fromId);
            Component received = Messages.get("msg.received", fromName, text);
            if (to == null) server.sendSystemMessage(received);
            else to.sendSystemMessage(received);
        }
        if (to != null && Afk.isAfk(to)) {
            String note = Afk.message(to);
            source.sendSystemMessage(note == null
                    ? Messages.get("msg.targetAfk", toName)
                    : Messages.get("msg.targetAfkMessage", toName, note));
        }

        Component spy = Messages.get("msg.spy", fromName, toName, text);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == from || p == to) continue;
            if (UserStore.get(p).socialSpy && Perms.has(p, "essentials.socialspy", Perms.OP)) p.sendSystemMessage(spy);
        }
        return 1;
    }

    public static void forget(UUID player) {
        lastFrom.remove(player);
        lastTo.remove(player);
    }

    private PrivateMessages() {}
}
