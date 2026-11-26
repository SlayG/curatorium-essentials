package dev.curatorium.essentials.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
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
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;

/** AFK, nicknames and real names. */
public final class PlayerCommands {
    public static void register() {
        CommandRegistry.add(new AfkCommand());
        CommandRegistry.add(new Nick());
        CommandRegistry.add(new RealName());
    }

    public static void registerEvents(IEventBus bus) {
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
