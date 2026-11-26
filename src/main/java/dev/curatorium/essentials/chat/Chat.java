package dev.curatorium.essentials.chat;

import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * EssentialsX Chat. Takes over every chat message: formats it per LuckPerms group, applies
 * local chat, shouting and ignores, and sends it to each player as a server message (which is
 * also how NoChatReports sends chat, so the two agree).
 */
public final class Chat {
    public enum Kind { NORMAL, LOCAL, SHOUT, QUESTION }

    public static void registerEvents(IEventBus bus) {
        // LOW: let other mods (filters, mutes) look at or cancel the message first.
        bus.addListener(EventPriority.LOW, ServerChatEvent.class, e -> {
            if (e.isCanceled()) return;
            e.setCanceled(true);
            send(e.getPlayer(), e.getRawText());
        });
    }

    /** Whether {@code receiver} has ignored the player {@code sender}. */
    public static boolean ignores(ServerPlayer receiver, UUID sender) {
        return sender != null && UserStore.get(receiver).ignored.contains(sender);
    }

    public static void send(ServerPlayer sender, String raw) {
        MinecraftServer server = sender.server;
        int radius = EssConfig.CHAT_RADIUS.get();
        Kind kind = Kind.NORMAL;
        String text = raw;
        if (radius > 0) {
            kind = Kind.LOCAL;
            if (text.length() > 1 && text.startsWith("!") && Perms.has(sender, "essentials.chat.shout", Perms.ALL)) {
                kind = Kind.SHOUT;
                text = text.substring(1).stripLeading();
            } else if (text.length() > 1 && text.startsWith("?") && Perms.has(sender, "essentials.chat.question", Perms.ALL)) {
                kind = Kind.QUESTION;
                text = text.substring(1).stripLeading();
            } else if (UserStore.get(sender).shout && Perms.has(sender, "essentials.chat.shout", Perms.ALL)) {
                kind = Kind.SHOUT;
            }
        }
        if (text.isBlank()) return;

        Component message = format(sender, text);
        MutableComponent line = Component.empty();
        switch (kind) {
            case LOCAL -> line.append(Messages.get("chat.type.local"));
            case SHOUT -> line.append(Messages.get("chat.type.shout"));
            case QUESTION -> line.append(Messages.get("chat.type.question"));
            default -> { }
        }
        line.append(message);

        List<ServerPlayer> heard = new ArrayList<>();
        double r2 = (double) radius * radius;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (kind == Kind.LOCAL && p != sender
                    && (p.level() != sender.level() || p.distanceToSqr(sender) > r2)) {
                if (Perms.has(p, "essentials.chat.spy", Perms.OP)) {
                    p.sendSystemMessage(Messages.get("chat.spy").append(line));
                }
                continue;
            }
            if (p != sender && ignores(p, sender.getUUID())) continue;
            p.sendSystemMessage(line);
            if (p != sender) heard.add(p);
        }
        server.sendSystemMessage(line);
        // Local and question chat stay in game; normal chat and shouts go to Discord.
        if (kind == Kind.NORMAL || kind == Kind.SHOUT) dev.curatorium.essentials.discord.Discord.chat(sender, text);
        if (kind == Kind.LOCAL && heard.isEmpty()) sender.sendSystemMessage(Messages.get("chat.nobodyHears"));
    }

    /** The message in the sender's chat format, with only the codes they're allowed. */
    public static Component format(ServerPlayer sender, String text) {
        Perms.Meta meta = Perms.meta(sender);
        String template = EssConfig.chatFormat(meta.group())
                .replace("{DISPLAYNAME}", "{0}")
                .replace("{MESSAGE}", "{1}")
                .replace("{PREFIX}", "{2}")
                .replace("{SUFFIX}", "{3}")
                .replace("{USERNAME}", "{4}")
                .replace("{NICKNAME}", "{5}")
                .replace("{GROUP}", "{6}")
                .replace("{WORLDNAME}", "{7}")
                .replace("{WORLD}", "{7}");
        Perms.TextPerms tp = Perms.textPerms(sender, "essentials.chat");
        String nick = UserStore.get(sender).nick;
        return Text.format(template,
                sender.getDisplayName(),
                Text.user(text, tp.color(), tp.format(), tp.magic(), tp.links()),
                Text.format(meta.prefix()),
                Text.format(meta.suffix()),
                sender.getGameProfile().getName(),
                nick == null ? Component.literal(sender.getGameProfile().getName()) : Text.format(nick),
                meta.group() == null ? "" : meta.group(),
                sender.level().dimension().location().getPath());
    }

    private Chat() {}
}
