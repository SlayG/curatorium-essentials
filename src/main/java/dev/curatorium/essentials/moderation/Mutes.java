package dev.curatorium.essentials.moderation;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Durations;
import dev.curatorium.essentials.util.Notify;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

/** /mute, and keeping muted players quiet: no chat, and no commands from {@code moderation.muteBlockedCommands}. */
public final class Mutes {
    public static void register() {
        CommandRegistry.add(new Mute());
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(EventPriority.HIGHEST, ServerChatEvent.class, e -> {
            if (checkMuted(e.getPlayer())) e.setCanceled(true);
        });
        bus.addListener(EventPriority.HIGH, CommandEvent.class, e -> {
            ServerPlayer p = e.getParseResults().getContext().getSource().getPlayer();
            if (p == null || e.isCanceled()) return;
            String label = Commands.label(e);
            if (label == null) return;
            EssCommand ess = CommandRegistry.byLabel(label);
            String name = ess != null ? ess.name() : label;
            if ((EssConfig.muteBlocks(name) || EssConfig.muteBlocks(label)) && checkMuted(p)) e.setCanceled(true);
        });
    }

    /** Small helpers for reading the command being run. */
    static final class Commands {
        /** The first word of the command (without a leading slash and namespace), lower case. */
        static String label(CommandEvent e) {
            String input = e.getParseResults().getReader().getString().trim();
            if (input.startsWith("/")) input = input.substring(1);
            if (input.isEmpty()) return null;
            String first = input.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            int colon = first.indexOf(':');
            return colon >= 0 ? first.substring(colon + 1) : first;
        }
    }

    public static boolean isMuted(ServerPlayer player) {
        UserData d = UserStore.get(player);
        long now = System.currentTimeMillis();
        if (d.muteUntil > 0 && d.muteUntil <= now) {
            d.muteUntil = 0;
            d.muteReason = null;
            d.markDirty();
            player.sendSystemMessage(Messages.get("mute.expired"));
        }
        return d.isMuted(now);
    }

    /** If the player is muted, tells them so and returns true. */
    public static boolean checkMuted(ServerPlayer player) {
        if (!isMuted(player)) return false;
        UserData d = UserStore.get(player);
        Component time = d.muteUntil == -1 ? Messages.get("mute.forever")
                : Component.literal(Durations.format(d.muteUntil - System.currentTimeMillis()));
        player.sendSystemMessage(d.muteReason == null
                ? Messages.get("mute.youAreMuted", time)
                : Messages.get("mute.youAreMutedReason", time, d.muteReason));
        return true;
    }

    /**
     * /mute <player> toggles; /mute <player> <time> [reason] or /mute <player> <reason> mutes
     * (timed or until unmuted). Alias /silence; /unmute only unmutes.
     */
    static final class Mute extends EssCommand {
        Mute() {
            super("mute", Perms.OP, "emute", "silence", "esilence", "unmute", "eunmute");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> mute(ctx, null, true)))
                    .then(textArg("time and reason").executes(exec(ctx -> mute(ctx, str(ctx, "time and reason"), false)))));
        }

        private int mute(CommandContext<CommandSourceStack> ctx, String text, boolean toggle) throws CommandSyntaxException {
            CommandSourceStack src = ctx.getSource();
            PlayerLookup.Target target = known(ctx, "player");
            if (!target.isOnline() && !Perms.has(src, "essentials.mute.offline", Perms.OP)) {
                return fail(ctx, Messages.get("mute.offlineDenied", target.name()));
            }
            UserData d = UserStore.get(target.uuid());
            long now = System.currentTimeMillis();
            String by = Bans.sourceName(src);
            String label = ctx.getNodes().get(0).getNode().getName();
            boolean unmuteOnly = label.endsWith("unmute");
            if (unmuteOnly || (toggle && d.isMuted(now))) {
                if (!d.isMuted(now)) return fail(ctx, Messages.get("mute.notMuted", target.name()));
                d.muteUntil = 0;
                d.muteReason = null;
                d.markDirty();
                if (target.isOnline()) target.online().sendSystemMessage(Messages.get("mute.unmuted"));
                Notify.staff(src.getServer(), "essentials.mute.notify", Messages.get("mute.notifyUnmuted", by, target.name()));
                return 1;
            }
            if (target.isOnline() && target.online() != src.getPlayer()
                    && Perms.has(target.online(), "essentials.mute.exempt", Perms.ADMIN)) {
                return fail(ctx, Messages.get("mute.exempt", target.name()));
            }
            Bans.TimeAndReason tr = Bans.TimeAndReason.parse(text, false);
            d.muteUntil = tr.millis().isPresent() ? now + tr.millis().getAsLong() : -1;
            d.muteReason = tr.reason() == null || tr.reason().isBlank() ? null : tr.reason().trim();
            d.markDirty();
            Component time = d.muteUntil == -1 ? Messages.get("mute.forever") : Component.literal(Durations.format(d.muteUntil - now));
            if (target.isOnline()) {
                target.online().sendSystemMessage(d.muteReason == null ? Messages.get("mute.youAreMuted", time)
                        : Messages.get("mute.youAreMutedReason", time, d.muteReason));
            }
            Notify.staff(src.getServer(), "essentials.mute.notify", Messages.get("mute.notify", by, target.name(), time,
                    d.muteReason == null ? Messages.raw("mute.noReason") : d.muteReason));
            return 1;
        }
    }

    private Mutes() {}
}
