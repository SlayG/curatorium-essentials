package dev.curatorium.essentials.discord;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.player.Vanish;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.Text;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import dev.curatorium.essentials.util.Tasks;

/** /discord and /discordbroadcast in game, and the game events that are posted to Discord. */
public final class DiscordCommands {
    public static void register() {
        CommandRegistry.add(new DiscordInvite());
        CommandRegistry.add(new DiscordBroadcast());
        CommandRegistry.add(new LinkCommand());
        CommandRegistry.add(new UnlinkCommand());
    }

    public static void registerEvents(IEventBus bus) {
        // LOW: after LuckPerms is hooked up (Perms.onServerStarted), which role sync needs.
        bus.addListener(EventPriority.LOW, ServerStartedEvent.class, e -> {
            Links.load();
            Discord.start(e.getServer());
            Discord.send("start", Discord.fill("discord.out.start"));
        });
        bus.addListener(ServerStoppingEvent.class, e -> Discord.stop(Discord.fill("discord.out.stop")));

        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            if (!Vanish.isVanished(p)) joined(p, true);
            Tasks.later(1, () -> Discord.updateStatus(p.server));
            Links.Link link = Links.get(p.getUUID());
            if (link != null) {
                Links.rename(p.getUUID(), p.getGameProfile().getName());
                if (Discord.running()) RoleSync.full(p.getUUID(), link.discordId(), p.getGameProfile().getName());
            }
        });
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            if (!Vanish.isVanished(p)) joined(p, false);
            Tasks.later(1, () -> Discord.updateStatus(p.server));
        });
        bus.addListener(EventPriority.LOWEST, LivingDeathEvent.class, e -> {
            if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p) || Vanish.isVanished(p)) return;
            if (!p.serverLevel().getGameRules().getBoolean(GameRules.RULE_SHOWDEATHMESSAGES)) return;
            Discord.send("death", Discord.fill("discord.out.death", Discord.escape(p.getCombatTracker().getDeathMessage().getString())));
        });
        bus.addListener(AdvancementEvent.AdvancementEarnEvent.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p) || Vanish.isVanished(p)) return;
            var display = e.getAdvancement().value().display().orElse(null);
            if (display == null || !display.shouldAnnounceChat()
                    || !p.serverLevel().getGameRules().getBoolean(GameRules.RULE_ANNOUNCE_ADVANCEMENTS)) return;
            String text = display.getType().createAnnouncement(e.getAdvancement(), p).getString();
            Discord.send("advancement", Discord.fill("discord.out.advancement", Discord.escape(text)));
        });
    }

    /** Join or leave as shown in Discord; also used for vanishing (a fake leave or join). */
    public static void joined(ServerPlayer p, boolean join) {
        Discord.send(join ? "join" : "leave",
                Discord.fill(join ? "discord.out.join" : "discord.out.leave", Discord.escape(p.getDisplayName().getString())));
    }

    /** From /vanish. */
    public static void vanished(ServerPlayer p, boolean vanished) {
        if (DiscordConfig.VANISH_FAKE.get()) joined(p, !vanished);
        Discord.updateStatus(p.server);
    }

    /** Staff notices (kicks, bans, mutes, jails), routed by their permission node. */
    public static void staffNotice(String node, Component message) {
        String type = switch (node) {
            case "essentials.kick.notify" -> "kick";
            case "essentials.ban.notify", "essentials.banip.notify" -> "ban";
            case "essentials.mute.notify" -> "mute";
            case "essentials.jail.notify" -> "jail";
            default -> null;
        };
        if (type != null) Discord.send(type, Discord.fill("discord.out.staff", Discord.escape(message.getString())));
    }

    /**
     * For the login mixin: the kick message for a player who must link first, or null to let
     * them in. Nobody is kept out while the bot is offline, or with essentials.link.bypass.
     */
    public static Component mustLink(net.minecraft.server.MinecraftServer server, com.mojang.authlib.GameProfile profile) {
        if (!DiscordConfig.LINK_REQUIRED.get() || !Discord.running() || Links.get(profile.getId()) != null) return null;
        Boolean bypass = Perms.luckPermsActive() ? dev.curatorium.essentials.perm.LuckPermsGroups.check(profile.getId(), "essentials.link.bypass") : null;
        if (bypass == null) bypass = server.getProfilePermissions(profile) >= Perms.ADMIN;
        if (bypass) return null;
        return Messages.get("link.kick", Links.codeFor(profile.getId(), profile.getName()));
    }

    /** /link: a code to enter in Discord, or which account you're linked to. */
    static final class LinkCommand extends EssCommand {
        LinkCommand() {
            super("link", Perms.ALL, "elink", "discordlink");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                Links.Link link = Links.get(me.getUUID());
                if (link != null) {
                    reply(ctx, Messages.get("link.already", link.discordName));
                    return 1;
                }
                if (!Discord.running()) return fail(ctx, Messages.get("discord.notRunning"));
                String code = Links.codeFor(me.getUUID(), me.getGameProfile().getName());
                Component shown = Component.literal(code).withStyle(s -> s
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Messages.get("link.copy"))));
                reply(ctx, Messages.get("link.code", shown, DiscordConfig.CODE_MINUTES.get()));
                return 1;
            }));
        }
    }

    /** /unlink, /unlink <player> (essentials.unlink.others). */
    static final class UnlinkCommand extends EssCommand {
        UnlinkCommand() {
            super("unlink", Perms.ALL, "eunlink", "discordunlink");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                return unlink(ctx, me.getUUID(), null);
            }));
            root.then(playerArg("player").requires(s -> has(s, "others", Perms.OP)).executes(exec(ctx -> {
                var target = known(ctx, str(ctx, "player"));
                return unlink(ctx, target.uuid(), target.name());
            })));
        }

        private static int unlink(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, java.util.UUID player, String other) {
            Links.Link link = Links.unlink(player);
            if (link == null) return fail(ctx, other == null ? Messages.get("unlink.none") : Messages.get("unlink.noneOther", other));
            RoleSync.onUnlinked(player, link.discordId());
            Discord.LOG.info("Discord: {} unlinked from {}", link.name, link.discordName);
            reply(ctx, other == null ? Messages.get("unlink.done", link.discordName) : Messages.get("unlink.doneOther", other, link.discordName));
            return 1;
        }
    }

    /** /discord: the invite link. */
    static final class DiscordInvite extends EssCommand {
        DiscordInvite() {
            super("discord", Perms.ALL, "ediscord");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                String url = DiscordConfig.INVITE.get().trim();
                if (url.isEmpty()) return fail(ctx, Messages.get("discord.noInvite"));
                Component link = Component.literal(url).withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)).withUnderlined(true));
                reply(ctx, Messages.get("discord.invite", link));
                return 1;
            }));
        }
    }

    /** /discordbroadcast <channel> <message>: posts as the bot to chat, staff, console or a channel id. */
    static final class DiscordBroadcast extends EssCommand {
        DiscordBroadcast() {
            super("discordbroadcast", Perms.OP, "dbroadcast", "dbc", "dbcast", "ediscordbroadcast");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("channel", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[] {"chat", "staff", "console"}, b))
                    .then(textArg("message").executes(exec(ctx -> {
                        if (!Discord.running()) return fail(ctx, Messages.get("discord.notRunning"));
                        String which = str(ctx, "channel").toLowerCase(Locale.ROOT);
                        long id = DiscordConfig.channel(which);
                        if (id == 0 || !Discord.post(id, Text.plain(str(ctx, "message")))) {
                            return fail(ctx, Messages.get("discord.badChannel", which));
                        }
                        reply(ctx, Messages.get("discord.broadcastSent"));
                        return 1;
                    }))));
        }
    }

    private DiscordCommands() {}
}
