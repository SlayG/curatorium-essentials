package dev.curatorium.essentials.moderation;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Durations;
import dev.curatorium.essentials.util.Notify;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.IpBanListEntry;
import net.minecraft.server.players.UserBanListEntry;

/**
 * Bans and kicks. Bans go in the vanilla ban lists ({@code banned-players.json},
 * {@code banned-ips.json}), so vanilla's login check enforces them, temporary bans expire by
 * themselves and {@code /pardon} works even with Essentials removed.
 */
public final class Bans {
    private static final Pattern IP = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$|^[0-9a-fA-F:]+:[0-9a-fA-F:]*$");

    public static void register() {
        CommandRegistry.add(new Ban("ban", false, "eban"));
        CommandRegistry.add(new Ban("tempban", true, "etempban"));
        CommandRegistry.add(new BanIp("banip", false, "ebanip", "ipban", "eipban", "ban-ip"));
        CommandRegistry.add(new BanIp("tempbanip", true, "etempbanip", "tempipban", "etempipban"));
        CommandRegistry.add(new Unban());
        CommandRegistry.add(new UnbanIp());
        CommandRegistry.add(new Kick());
        CommandRegistry.add(new KickAll());
    }

    static String sourceName(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p == null ? Messages.raw("console.plainName") : p.getGameProfile().getName();
    }

    static String reasonOr(String reason) {
        return reason == null || reason.isBlank() ? EssConfig.BAN_DEFAULT_REASON.get() : reason.trim();
    }

    /** "<time> [reason]" split into the time and the reason; time is empty if the first word isn't one. */
    record TimeAndReason(OptionalLong millis, String reason) {
        static TimeAndReason parse(String text, boolean timeRequired) {
            if (text == null) return new TimeAndReason(OptionalLong.empty(), null);
            String[] parts = text.trim().split("\\s+", 2);
            OptionalLong t = Durations.parse(parts[0]);
            if (t.isPresent()) return new TimeAndReason(t, parts.length > 1 ? parts[1] : null);
            return new TimeAndReason(OptionalLong.empty(), timeRequired ? null : text.trim());
        }
    }

    private static Component kickMessage(String key, String reason, String by, long expires) {
        return Messages.get(key, reason, by, expires > 0 ? Durations.format(expires - System.currentTimeMillis()) : "");
    }

    // ---------------------------------------------------------------- ban / tempban

    /** /ban <player> [reason], /tempban <player> <time> [reason] */
    static final class Ban extends EssCommand {
        private final boolean temp;

        Ban(String name, boolean temp, String... aliases) {
            super(name, Perms.OP, aliases);
            this.temp = temp;
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            var player = playerArg("player");
            if (!temp) player.executes(exec(ctx -> ban(ctx, null)));
            root.then(player.then(textArg(temp ? "time and reason" : "reason")
                    .executes(exec(ctx -> ban(ctx, str(ctx, temp ? "time and reason" : "reason"))))));
        }

        private int ban(CommandContext<CommandSourceStack> ctx, String text) throws CommandSyntaxException {
            CommandSourceStack src = ctx.getSource();
            PlayerLookup.Target target = known(ctx, "player");
            long expires = 0;
            String reason = text;
            if (temp) {
                TimeAndReason tr = TimeAndReason.parse(text, true);
                if (tr.millis().isEmpty()) return fail(ctx, Messages.get("duration.invalid", text.split("\\s+")[0]));
                expires = System.currentTimeMillis() + tr.millis().getAsLong();
                reason = tr.reason();
            }
            if (target.isOnline() ? Perms.has(target.online(), "essentials.ban.exempt", Perms.ADMIN)
                    : !Perms.has(src, "essentials.ban.offline", Perms.OP)) {
                return fail(ctx, Messages.get(target.isOnline() ? "ban.exempt" : "ban.offlineDenied", target.name()));
            }
            reason = reasonOr(reason);
            String by = sourceName(src);
            MinecraftServer server = src.getServer();
            GameProfile profile = new GameProfile(target.uuid(), target.name());
            server.getPlayerList().getBans().add(new UserBanListEntry(profile, new Date(), by,
                    expires > 0 ? new Date(expires) : null, reason));
            if (target.isOnline()) {
                target.online().connection.disconnect(kickMessage(expires > 0 ? "ban.tempKick" : "ban.kick", reason, by, expires));
            }
            Notify.staff(server, "essentials.ban.notify", expires > 0
                    ? Messages.get("ban.tempNotify", by, target.name(), Durations.format(expires - System.currentTimeMillis()), reason)
                    : Messages.get("ban.notify", by, target.name(), reason));
            return 1;
        }
    }

    // ---------------------------------------------------------------- banip / tempbanip

    /** /banip <player|address> [reason], /tempbanip <player|address> <time> [reason] */
    static final class BanIp extends EssCommand {
        private final boolean temp;

        BanIp(String name, boolean temp, String... aliases) {
            super(name, Perms.OP, aliases);
            this.temp = temp;
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            // A greedy argument: an address with ':' (IPv6) or '.' isn't a single brigadier word.
            root.then(textArg("target").suggests(PlayerLookup.ONLINE).executes(exec(ctx -> {
                String[] parts = str(ctx, "target").trim().split("\\s+", 2);
                return ban(ctx, parts[0], parts.length > 1 ? parts[1] : null);
            })));
        }

        private int ban(CommandContext<CommandSourceStack> ctx, String who, String text) {
            CommandSourceStack src = ctx.getSource();
            MinecraftServer server = src.getServer();
            String ip = resolveIp(server, who);
            if (ip == null) return fail(ctx, Messages.get("banip.unknown", who));
            long expires = 0;
            String reason = text;
            if (temp) {
                TimeAndReason tr = TimeAndReason.parse(text, true);
                if (tr.millis().isEmpty()) return fail(ctx, Messages.get("banip.tempUsage"));
                expires = System.currentTimeMillis() + tr.millis().getAsLong();
                reason = tr.reason();
            }
            reason = reasonOr(reason);
            String by = sourceName(src);
            server.getPlayerList().getIpBans().add(new IpBanListEntry(ip, new Date(), by,
                    expires > 0 ? new Date(expires) : null, reason));
            List<ServerPlayer> kicked = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (ip.equals(p.getIpAddress())) kicked.add(p);
            }
            for (ServerPlayer p : kicked) {
                p.connection.disconnect(kickMessage(expires > 0 ? "ban.tempKick" : "ban.kick", reason, by, expires));
            }
            Notify.staff(server, "essentials.banip.notify", Messages.get("banip.notify", by, ip, reason, kicked.size()));
            return 1;
        }
    }

    /** An address as typed, or a known player's last address (online first). */
    static String resolveIp(MinecraftServer server, String who) {
        if (IP.matcher(who).matches()) return who;
        var online = PlayerLookup.online(server, who);
        if (online.isPresent()) return online.get().getIpAddress();
        return UserStore.byName(who).map(k -> UserStore.get(k.uuid()).lastIp).filter(s -> !s.isEmpty()).orElse(null);
    }

    // ---------------------------------------------------------------- unban / unbanip

    /** /unban <player> */
    static final class Unban extends EssCommand {
        Unban() {
            super("unban", Perms.OP, "pardon", "eunban", "epardon");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    ctx.getSource().getServer().getPlayerList().getBans().getUserList(), b)).executes(exec(ctx -> {
                String name = str(ctx, "player");
                MinecraftServer server = ctx.getSource().getServer();
                var bans = server.getPlayerList().getBans();
                for (UserBanListEntry e : new ArrayList<>(bans.getEntries())) {
                    if (e.getDisplayName().getString().equalsIgnoreCase(name)) {
                        bans.remove(e);
                        Notify.staff(server, "essentials.ban.notify", Messages.get("unban.notify", sourceName(ctx.getSource()), e.getDisplayName().getString()));
                        return 1;
                    }
                }
                // A renamed player: match by UUID through our own name index.
                var known = UserStore.byName(name);
                if (known.isPresent()) {
                    GameProfile profile = new GameProfile(known.get().uuid(), known.get().name());
                    if (bans.isBanned(profile)) {
                        bans.remove(profile);
                        Notify.staff(server, "essentials.ban.notify", Messages.get("unban.notify", sourceName(ctx.getSource()), known.get().name()));
                        return 1;
                    }
                }
                return fail(ctx, Messages.get("unban.notBanned", name));
            })));
        }
    }

    /** /unbanip <address|player> */
    static final class UnbanIp extends EssCommand {
        UnbanIp() {
            super("unbanip", Perms.OP, "pardon-ip", "eunbanip", "pardonip", "epardonip");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(textArg("target").suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    ctx.getSource().getServer().getPlayerList().getIpBans().getUserList(), b)).executes(exec(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();
                String who = str(ctx, "target").trim();
                String ip = resolveIp(server, who);
                var bans = server.getPlayerList().getIpBans();
                if (ip == null || !bans.isBanned(ip)) return fail(ctx, Messages.get("unbanip.notBanned", who));
                bans.remove(ip);
                Notify.staff(server, "essentials.banip.notify", Messages.get("unbanip.notify", sourceName(ctx.getSource()), ip));
                return 1;
            })));
        }
    }

    // ---------------------------------------------------------------- kick / kickall

    /** /kick <player> [reason] */
    static final class Kick extends EssCommand {
        Kick() {
            super("kick", Perms.OP, "ekick");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> kick(ctx, null)))
                    .then(textArg("reason").executes(exec(ctx -> kick(ctx, str(ctx, "reason"))))));
        }

        private int kick(CommandContext<CommandSourceStack> ctx, String reason) throws CommandSyntaxException {
            ServerPlayer target = online(ctx, "player");
            if (target != ctx.getSource().getPlayer() && Perms.has(target, "essentials.kick.exempt", Perms.ADMIN)) {
                return fail(ctx, Messages.get("kick.exempt", target.getDisplayName()));
            }
            String by = sourceName(ctx.getSource());
            String why = reason == null || reason.isBlank() ? Messages.raw("kick.defaultReason") : reason.trim();
            target.connection.disconnect(Messages.get("kick.message", why, by));
            Notify.staff(ctx.getSource().getServer(), "essentials.kick.notify", Messages.get("kick.notify", by, target.getGameProfile().getName(), why));
            return 1;
        }
    }

    /** /kickall [reason]: everyone except the sender and players with essentials.kickall.exempt. */
    static final class KickAll extends EssCommand {
        KickAll() {
            super("kickall", Perms.ADMIN, "ekickall");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> kickAll(ctx, null)))
                    .then(textArg("reason").executes(exec(ctx -> kickAll(ctx, str(ctx, "reason")))));
        }

        private int kickAll(CommandContext<CommandSourceStack> ctx, String reason) {
            String by = sourceName(ctx.getSource());
            String why = reason == null || reason.isBlank() ? Messages.raw("kick.defaultReason") : reason.trim();
            int n = 0;
            for (ServerPlayer p : new ArrayList<>(ctx.getSource().getServer().getPlayerList().getPlayers())) {
                if (p == ctx.getSource().getPlayer() || Perms.has(p, "essentials.kickall.exempt", Perms.ADMIN)) continue;
                p.connection.disconnect(Messages.get("kick.message", why, by));
                n++;
            }
            reply(ctx, Messages.get("kickall.done", n));
            return 1;
        }
    }

    private Bans() {}
}
