package dev.curatorium.essentials.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.player.Afk;
import dev.curatorium.essentials.player.Names;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.Text;
import dev.curatorium.essentials.util.Durations;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Mail, AFK, nicknames and real names. */
public final class PlayerCommands {
    public static void register() {
        CommandRegistry.add(new Mail());
        CommandRegistry.add(new AfkCommand());
        CommandRegistry.add(new Nick());
        CommandRegistry.add(new RealName());
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p && EssConfig.MAIL_ON_JOIN.get()) Mail.notifyUnread(p);
        });
    }

    // ---------------------------------------------------------------- mail

    /** /mail read [page] | clear [number] | send <player> <msg> | sendtemp <player> <time> <msg> | sendall <msg> */
    static final class Mail extends EssCommand {
        private static final int PAGE = 5;
        private static final Map<UUID, Deque<Long>> recentSends = new ConcurrentHashMap<>();

        Mail() {
            super("mail", Perms.ALL, "email", "eemail", "memo", "ememo");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> read(ctx, 1)))
                    .then(Commands.literal("read").executes(exec(ctx -> read(ctx, 1)))
                            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                    .executes(exec(ctx -> read(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
                    .then(Commands.literal("clear").executes(exec(ctx -> clear(ctx, 0)))
                            .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                    .executes(exec(ctx -> clear(ctx, IntegerArgumentType.getInteger(ctx, "number"))))))
                    .then(Commands.literal("send").requires(src -> has(src, "send", Perms.ALL))
                            .then(playerArg("player").then(textArg("message")
                                    .executes(exec(ctx -> send(ctx, known(ctx, "player"), str(ctx, "message"), 0))))))
                    .then(Commands.literal("sendtemp").requires(src -> has(src, "sendtemp", Perms.OP))
                            .then(playerArg("player").then(Commands.argument("expire", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .then(textArg("message").executes(exec(ctx -> {
                                        OptionalLong time = Durations.parse(str(ctx, "expire"));
                                        if (time.isEmpty()) return fail(ctx, Messages.get("duration.invalid", str(ctx, "expire")));
                                        return send(ctx, known(ctx, "player"), str(ctx, "message"), time.getAsLong());
                                    }))))))
                    .then(Commands.literal("sendall").requires(src -> has(src, "sendall", Perms.OP))
                            .then(textArg("message").executes(exec(this::sendAll))));
        }

        static void notifyUnread(ServerPlayer p) {
            long now = System.currentTimeMillis();
            long unread = UserStore.get(p).mail.stream().filter(m -> !m.read && !m.expired(now)).count();
            if (unread > 0) p.sendSystemMessage(Messages.get("mail.unread", unread));
        }

        private int read(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            UserData d = UserStore.get(me);
            long now = System.currentTimeMillis();
            d.mail.removeIf(m -> m.expired(now));
            if (d.mail.isEmpty()) {
                reply(ctx, Messages.get("mail.none"));
                return 1;
            }
            int pages = (d.mail.size() + PAGE - 1) / PAGE;
            int p = Math.min(page, pages);
            reply(ctx, Messages.get("mail.header", p, pages));
            for (int i = (p - 1) * PAGE; i < Math.min(d.mail.size(), p * PAGE); i++) {
                UserData.Mail m = d.mail.get(i);
                reply(ctx, Messages.get(m.read ? "mail.line" : "mail.lineUnread",
                        i + 1, m.senderName, Durations.format(now - m.sent), m.message));
                m.read = true;
            }
            if (p < pages) reply(ctx, Messages.get("page.next", p, pages, "/mail read " + (p + 1)));
            reply(ctx, Messages.get("mail.clearHint"));
            d.markDirty();
            return 1;
        }

        private int clear(CommandContext<CommandSourceStack> ctx, int number) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            UserData d = UserStore.get(me);
            if (number == 0) {
                d.mail.clear();
                reply(ctx, Messages.get("mail.cleared"));
            } else {
                if (number > d.mail.size()) return fail(ctx, Messages.get("mail.noSuch", number));
                d.mail.remove(number - 1);
                reply(ctx, Messages.get("mail.clearedOne", number));
            }
            d.markDirty();
            return 1;
        }

        private boolean rateLimited(CommandContext<CommandSourceStack> ctx) {
            ServerPlayer me = ctx.getSource().getPlayer();
            if (me == null || Perms.has(me, "essentials.mail.nolimit", Perms.OP)) return false;
            long now = System.currentTimeMillis();
            Deque<Long> sends = recentSends.computeIfAbsent(me.getUUID(), id -> new ArrayDeque<>());
            while (!sends.isEmpty() && now - sends.peekFirst() > 60_000) sends.pollFirst();
            if (sends.size() >= EssConfig.MAILS_PER_MINUTE.get()) return true;
            sends.addLast(now);
            return false;
        }

        private UserData.Mail make(CommandContext<CommandSourceStack> ctx, String message, long expiresIn) {
            ServerPlayer me = ctx.getSource().getPlayer();
            UserData.Mail m = new UserData.Mail();
            m.sender = me == null ? null : me.getUUID();
            m.senderName = me == null ? Messages.raw("console.plainName") : me.getGameProfile().getName();
            m.sent = System.currentTimeMillis();
            m.expires = expiresIn > 0 ? m.sent + expiresIn : 0;
            m.message = Text.plain(message);
            return m;
        }

        private int send(CommandContext<CommandSourceStack> ctx, PlayerLookup.Target to, String message, long expiresIn) {
            if (ctx.getSource().getPlayer() != null && dev.curatorium.essentials.moderation.Mutes.checkMuted(ctx.getSource().getPlayer())) return 0;
            if (rateLimited(ctx)) return fail(ctx, Messages.get("mail.tooFast"));
            UserData target = UserStore.get(to.uuid());
            ServerPlayer me = ctx.getSource().getPlayer();
            // An ignored sender is told it was sent; the mail just never arrives.
            boolean ignored = me != null && target.ignored.contains(me.getUUID());
            if (!ignored) {
                target.mail.add(make(ctx, message, expiresIn));
                target.markDirty();
                if (to.isOnline()) to.online().sendSystemMessage(Messages.get("mail.received", ctx.getSource().getDisplayName()));
            }
            reply(ctx, Messages.get("mail.sent", to.name()));
            return 1;
        }

        private int sendAll(CommandContext<CommandSourceStack> ctx) {
            if (ctx.getSource().getPlayer() != null && dev.curatorium.essentials.moderation.Mutes.checkMuted(ctx.getSource().getPlayer())) return 0;
            String message = str(ctx, "message");
            int count = 0;
            for (UUID id : UserStore.allKnown()) {
                UserData d = UserStore.get(id);
                d.mail.add(make(ctx, message, 0));
                d.markDirty();
                count++;
                ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayer(id);
                if (p != null) p.sendSystemMessage(Messages.get("mail.received", ctx.getSource().getDisplayName()));
            }
            reply(ctx, Messages.get("mail.sentAll", count));
            return 1;
        }
    }

    // ---------------------------------------------------------------- afk

    /** /afk [message], or /afk <player> [message] with essentials.afk.others. */
    static final class AfkCommand extends EssCommand {
        AfkCommand() {
            super("afk", Perms.ALL, "eafk", "away", "eaway");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> toggle(ctx, self(ctx), null)))
                    .then(textArg("message").executes(exec(ctx -> {
                        String text = str(ctx, "message");
                        String first = text.split(" ", 2)[0];
                        if (has(ctx.getSource(), "others", Perms.OP)) {
                            var other = PlayerLookup.online(ctx.getSource(), first);
                            if (other.isPresent() && other.get() != ctx.getSource().getPlayer()
                                    && other.get().getGameProfile().getName().equalsIgnoreCase(first)) {
                                String rest = text.length() > first.length() ? text.substring(first.length()).trim() : null;
                                return toggle(ctx, other.get(), rest);
                            }
                        }
                        ServerPlayer me = self(ctx);
                        if (!has(ctx.getSource(), "message", Perms.ALL)) return toggle(ctx, me, null);
                        return toggle(ctx, me, Text.plain(text));
                    })));
        }

        private int toggle(CommandContext<CommandSourceStack> ctx, ServerPlayer target, String message) {
            Afk.setAfk(target, !Afk.isAfk(target), message == null || message.isBlank() ? null : message);
            return 1;
        }
    }

    // ---------------------------------------------------------------- nick

    /** /nick <nickname|off>, or /nick <player> <nickname|off> with essentials.nick.others. */
    static final class Nick extends EssCommand {
        private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_]+");

        Nick() {
            super("nick", Perms.OP, "enick", "nickname", "enickname");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            // One greedy argument, split by hand: a single word argument can't hold "&" codes.
            root.then(textArg("args").suggests(PlayerLookup.ONLINE).executes(exec(ctx -> {
                String[] parts = str(ctx, "args").trim().split("\\s+");
                if (parts.length == 1) return set(ctx, self(ctx), parts[0]);
                if (parts.length != 2) return fail(ctx, Messages.get("nick.usage"));
                if (!has(ctx.getSource(), "others", Perms.OP)) return fail(ctx, Messages.get("command.noPermission"));
                ServerPlayer target = online(ctx.getSource(), parts[0]);
                return set(ctx, target, parts[1]);
            })));
        }

        private int set(CommandContext<CommandSourceStack> ctx, ServerPlayer target, String input) {
            UserData d = UserStore.get(target);
            String real = target.getGameProfile().getName();
            if (input.equalsIgnoreCase("off") || input.equals(real)) {
                d.nick = null;
                d.markDirty();
                Names.refresh(target);
                target.sendSystemMessage(Messages.get("nick.removed"));
                if (ctx.getSource().getPlayer() != target) reply(ctx, Messages.get("nick.removedOther", real));
                return 1;
            }
            CommandSourceStack src = ctx.getSource();
            String nick = Text.strip(input, has(src, "color", Perms.OP), has(src, "format", Perms.OP), has(src, "magic", Perms.OP));
            String plain = Text.plain(nick);
            if (plain.isEmpty() || !ALLOWED.matcher(plain).matches()) return fail(ctx, Messages.get("nick.invalid"));
            if (plain.length() > EssConfig.NICK_MAX_LENGTH.get() && !has(src, "allowlong", Perms.ADMIN)) {
                return fail(ctx, Messages.get("nick.tooLong", EssConfig.NICK_MAX_LENGTH.get()));
            }
            if (taken(src.getServer(), target, plain.toLowerCase(Locale.ROOT))) return fail(ctx, Messages.get("nick.taken"));
            d.nick = nick;
            d.markDirty();
            Names.refresh(target);
            target.sendSystemMessage(Messages.get("nick.set", target.getDisplayName()));
            if (src.getPlayer() != target) reply(ctx, Messages.get("nick.setOther", real, target.getDisplayName()));
            return 1;
        }

        /** Another player's real name or an online player's nickname. */
        private static boolean taken(MinecraftServer server, ServerPlayer target, String plainLower) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p == target) continue;
                if (p.getGameProfile().getName().equalsIgnoreCase(plainLower) || plainLower.equals(Names.plainNick(p))) return true;
            }
            return UserStore.byName(plainLower).filter(k -> !k.uuid().equals(target.getUUID())).isPresent();
        }
    }

    /** /realname <nickname> */
    static final class RealName extends EssCommand {
        RealName() {
            super("realname", Perms.ALL, "erealname");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("nickname", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(exec(ctx -> {
                String needle = Text.plain(str(ctx, "nickname")).toLowerCase(Locale.ROOT);
                List<ServerPlayer> found = new ArrayList<>();
                for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                    String nick = Names.plainNick(p);
                    if (nick != null && nick.contains(needle)) found.add(p);
                }
                if (found.isEmpty()) return fail(ctx, Messages.get("realname.none"));
                for (ServerPlayer p : found) reply(ctx, Messages.get("realname.result", p.getDisplayName(), p.getGameProfile().getName()));
                return 1;
            })));
        }
    }

    private PlayerCommands() {}
}
