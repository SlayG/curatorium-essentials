package dev.curatorium.essentials.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.chat.Chat;
import dev.curatorium.essentials.chat.PrivateMessages;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.Text;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Private messages, ignores and broadcasts. */
public final class MessagingCommands {
    public static void register() {
        CommandRegistry.add(new Msg());
        CommandRegistry.add(new Reply());
        CommandRegistry.add(new RToggle());
        CommandRegistry.add(new MsgToggle());
        CommandRegistry.add(new SocialSpy());
        CommandRegistry.add(new Ignore());
        CommandRegistry.add(new Me());
        CommandRegistry.add(new HelpOp());
        CommandRegistry.add(new Broadcast());
        CommandRegistry.add(new BroadcastWorld());
        CommandRegistry.add(new ToggleShout());
    }

    private static boolean isConsoleName(String s) {
        return s.equalsIgnoreCase("console") || s.equalsIgnoreCase("server");
    }

    /** /msg <player> <message> */
    static final class Msg extends EssCommand {
        Msg() {
            super("msg", Perms.ALL, "w", "m", "t", "pm", "emsg", "epm", "tell", "etell", "whisper", "ewhisper");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").then(textArg("message").executes(exec(ctx -> {
                String name = str(ctx, "player");
                if (isConsoleName(name)) return PrivateMessages.send(ctx.getSource(), null, str(ctx, "message"));
                return PrivateMessages.send(ctx.getSource(), online(ctx, "player"), str(ctx, "message"));
            }))));
        }
    }

    /** /r <message> */
    static final class Reply extends EssCommand {
        Reply() {
            super("r", Perms.ALL, "er", "reply", "ereply");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(textArg("message").executes(exec(ctx -> {
                UUID target = PrivateMessages.replyTarget(ctx.getSource());
                if (target == null) return fail(ctx, Messages.get("reply.noTarget"));
                if (target.equals(PrivateMessages.CONSOLE)) return PrivateMessages.send(ctx.getSource(), null, str(ctx, "message"));
                ServerPlayer to = ctx.getSource().getServer().getPlayerList().getPlayer(target);
                if (to == null) return fail(ctx, Messages.get("reply.offline"));
                return PrivateMessages.send(ctx.getSource(), to, str(ctx, "message"));
            })));
        }
    }

    /** Toggles a boolean in the player's data, optionally for another player with .others. */
    private abstract static class Toggle extends EssCommand {
        Toggle(String name, int level, String... aliases) {
            super(name, level, aliases);
        }

        abstract boolean get(UserData d);

        abstract void set(UserData d, boolean v);

        abstract String messageKey();

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> toggle(ctx, self(ctx))))
                    .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                            .executes(exec(ctx -> toggle(ctx, online(ctx, "player")))));
        }

        private int toggle(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
            UserData d = UserStore.get(target);
            boolean v = !get(d);
            set(d, v);
            d.markDirty();
            Component state = Messages.get(v ? "toggle.on" : "toggle.off");
            target.sendSystemMessage(Messages.get(messageKey(), state));
            if (ctx.getSource().getPlayer() != target) {
                reply(ctx, Messages.get("toggle.other", Messages.get(messageKey(), state), target.getDisplayName()));
            }
            return 1;
        }
    }

    static final class RToggle extends Toggle {
        RToggle() {
            super("rtoggle", Perms.ALL, "ertoggle", "replytoggle", "ereplytoggle");
        }

        boolean get(UserData d) { return d.replyToSent; }

        void set(UserData d, boolean v) { d.replyToSent = v; }

        String messageKey() { return "rtoggle.state"; }
    }

    static final class MsgToggle extends Toggle {
        MsgToggle() {
            super("msgtoggle", Perms.ALL, "emsgtoggle");
        }

        // The toggle shows whether messages are *blocked*.
        boolean get(UserData d) { return d.msgDisabled; }

        void set(UserData d, boolean v) { d.msgDisabled = v; }

        String messageKey() { return "msgtoggle.state"; }
    }

    static final class SocialSpy extends Toggle {
        SocialSpy() {
            super("socialspy", Perms.OP, "esocialspy");
        }

        boolean get(UserData d) { return d.socialSpy; }

        void set(UserData d, boolean v) { d.socialSpy = v; }

        String messageKey() { return "socialspy.state"; }
    }

    static final class ToggleShout extends Toggle {
        ToggleShout() {
            super("toggleshout", Perms.ALL, "etoggleshout", "ts", "ets");
        }

        boolean get(UserData d) { return d.shout; }

        void set(UserData d, boolean v) { d.shout = v; }

        String messageKey() { return "toggleshout.state"; }
    }

    /** /ignore [player]: toggles, or lists with no argument. */
    static final class Ignore extends EssCommand {
        Ignore() {
            super("ignore", Perms.ALL, "eignore", "unignore", "eunignore", "delignore", "edelignore",
                    "remignore", "eremignore", "rmignore", "ermignore");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(this::list)).then(playerArg("player").executes(exec(this::toggle)));
        }

        private int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            UserData d = UserStore.get(me);
            if (d.ignored.isEmpty()) {
                reply(ctx, Messages.get("ignore.none"));
                return 1;
            }
            List<String> names = new ArrayList<>();
            for (UUID id : d.ignored) names.add(nameOf(ctx.getSource().getServer(), id));
            names.sort(String.CASE_INSENSITIVE_ORDER);
            reply(ctx, Messages.get("ignore.list", String.join(", ", names)));
            return 1;
        }

        private int toggle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            PlayerLookup.Target t = known(ctx, "player");
            if (t.uuid().equals(me.getUUID())) return fail(ctx, Messages.get("ignore.self"));
            UserData d = UserStore.get(me);
            if (d.ignored.remove(t.uuid())) {
                d.markDirty();
                reply(ctx, Messages.get("ignore.removed", t.name()));
                return 1;
            }
            if (t.isOnline() && Perms.has(t.online(), "essentials.ignore.exempt", Perms.OP)) {
                return fail(ctx, Messages.get("ignore.exempt", t.name()));
            }
            d.ignored.add(t.uuid());
            d.markDirty();
            reply(ctx, Messages.get("ignore.added", t.name()));
            return 1;
        }
    }

    static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) return p.getGameProfile().getName();
        if (UserStore.exists(id)) {
            String n = UserStore.get(id).lastName;
            if (!n.isEmpty()) return n;
        }
        if (server.getProfileCache() != null) {
            var gp = server.getProfileCache().get(id);
            if (gp.isPresent()) return gp.get().getName();
        }
        return id.toString();
    }

    /** /me <action> */
    static final class Me extends EssCommand {
        Me() {
            super("me", Perms.ALL, "action", "eaction", "describe", "edescribe", "eme");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(textArg("action").executes(exec(ctx -> {
                CommandSourceStack src = ctx.getSource();
                ServerPlayer from = src.getPlayer();
                Component text;
                Component name;
                if (from == null) {
                    text = Text.user(str(ctx, "action"), true, true, true, true);
                    name = Messages.get("console.name");
                } else {
                    Perms.TextPerms tp = Perms.textPerms(from, "essentials.chat");
                    text = Text.user(str(ctx, "action"), tp.color(), tp.format(), tp.magic(), tp.links());
                    name = from.getDisplayName();
                }
                Component line = Messages.get("me.format", name, text);
                for (ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
                    if (from != null && p != from && Chat.ignores(p, from.getUUID())) continue;
                    p.sendSystemMessage(line);
                }
                src.getServer().sendSystemMessage(line);
                return 1;
            })));
        }
    }

    /** /helpop <message>: to staff (essentials.helpop.receive) and the console. */
    static final class HelpOp extends EssCommand {
        HelpOp() {
            super("helpop", Perms.ALL, "ac", "eac", "amsg", "eamsg", "ehelpop");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(textArg("message").executes(exec(ctx -> {
                CommandSourceStack src = ctx.getSource();
                ServerPlayer from = src.getPlayer();
                Component name = from == null ? Messages.get("console.name") : from.getDisplayName();
                Component line = Messages.get("helpop.format", name, str(ctx, "message"));
                boolean selfSees = false;
                for (ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
                    if (Perms.has(p, "essentials.helpop.receive", Perms.OP)) {
                        p.sendSystemMessage(line);
                        if (p == from) selfSees = true;
                    }
                }
                src.getServer().sendSystemMessage(line);
                dev.curatorium.essentials.discord.Discord.send("helpop", dev.curatorium.essentials.discord.Discord.fill("discord.out.helpop",
                        dev.curatorium.essentials.discord.Discord.escape(name.getString()), dev.curatorium.essentials.discord.Discord.escape(str(ctx, "message"))));
                if (from != null && !selfSees) from.sendSystemMessage(Messages.get("helpop.sent"));
                return 1;
            })));
        }
    }

    /** /broadcast <message>: to everyone, colour codes allowed. */
    static final class Broadcast extends EssCommand {
        Broadcast() {
            super("broadcast", Perms.OP, "bc", "ebc", "bcast", "ebcast", "ebroadcast", "shout", "eshout");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(textArg("message").executes(exec(ctx -> {
                Component line = Messages.get("broadcast.format", Text.format(str(ctx, "message")));
                ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(line, false);
                dev.curatorium.essentials.discord.Discord.send("broadcast", dev.curatorium.essentials.discord.Discord.fill("discord.out.broadcast",
                        Text.plain(str(ctx, "message"))));
                return 1;
            })));
        }
    }

    /** /broadcastworld <message>: to everyone in the sender's dimension. */
    static final class BroadcastWorld extends EssCommand {
        BroadcastWorld() {
            super("broadcastworld", Perms.OP, "bcw", "ebcw", "bcastw", "ebcastw", "ebroadcastworld", "shoutworld", "eshoutworld");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(textArg("message").executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                Component line = Messages.get("broadcast.format", Text.format(str(ctx, "message")));
                for (ServerPlayer p : me.serverLevel().players()) p.sendSystemMessage(line);
                ctx.getSource().getServer().sendSystemMessage(Component.literal(
                        "[" + me.level().dimension().location() + "] ").append(line));
                return 1;
            })));
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private MessagingCommands() {}
}
