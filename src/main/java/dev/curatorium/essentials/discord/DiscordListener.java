package dev.curatorium.essentials.discord;

import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.player.Vanish;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.CloseCode;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Discord events: logging in, messages in the relay channels, and the slash commands. */
final class DiscordListener extends ListenerAdapter {
    private final MinecraftServer server;
    private boolean warnedNoContent;

    DiscordListener(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void onReady(ReadyEvent e) {
        Discord.LOG.info("Discord: logged in as {} ({} server(s))", e.getJDA().getSelfUser().getName(), e.getGuildTotalCount());
        List<SlashCommandData> commands = List.of(
                Commands.slash("list", "Who's online on the Minecraft server"),
                Commands.slash("execute", "Run a server command (command roles only)")
                        .addOption(OptionType.STRING, "command", "The command, without the /", true),
                Commands.slash("msg", "Send a private message to a player in game")
                        .addOption(OptionType.STRING, "player", "Their Minecraft name", true)
                        .addOption(OptionType.STRING, "message", "What to say", true));
        String guildId = DiscordConfig.GUILD.get().trim();
        Guild guild = guildId.isEmpty() ? null : e.getJDA().getGuildById(guildId);
        if (guild != null) {
            guild.updateCommands().addCommands(commands).queue(null,
                    err -> Discord.LOG.warn("Could not register slash commands: {}", err.getMessage()));
        } else {
            if (!guildId.isEmpty()) Discord.LOG.warn("Discord: the bot isn't in the server {} (bot.guild); slash commands are registered globally", guildId);
            e.getJDA().updateCommands().addCommands(commands).queue(null,
                    err -> Discord.LOG.warn("Could not register slash commands: {}", err.getMessage()));
        }
        for (String which : List.of("chat", "staff", "console")) {
            long id = DiscordConfig.channel(which);
            if (id == 0) continue;
            var any = e.getJDA().getGuildChannelById(id);
            if (any == null) {
                Discord.LOG.warn("Discord: the bot can't see the {} channel {}: give it View Channel there (private channels and categories hide it), or check the ID", which,
                        Long.toUnsignedString(id));
            } else if (!(any instanceof net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel)) {
                Discord.LOG.warn("Discord: the {} channel #{} is a {} channel, not one messages can be sent to", which, any.getName(), any.getType());
            } else {
                Discord.LOG.info("Discord: {} channel is #{} in {}", which, any.getName(), any.getGuild().getName());
            }
        }
        long console = DiscordConfig.channel("console");
        if (console != 0 && (console == DiscordConfig.channel("chat") || console == DiscordConfig.channel("staff"))) {
            Discord.LOG.warn("Discord: the console channel is also the chat or staff channel; messages there are taken as commands, not chat");
        }
        Discord.onReady();
        server.execute(() -> Discord.updateStatus(server));
    }

    @Override
    public void onShutdown(ShutdownEvent e) {
        CloseCode code = e.getCloseCode();
        if (code == CloseCode.DISALLOWED_INTENTS) {
            Discord.LOG.error("Discord refused the login: turn on the Message Content Intent on the bot's page in the developer portal");
        } else if (code == CloseCode.AUTHENTICATION_FAILED) {
            Discord.LOG.error("Discord refused the login: the bot token is wrong (reset it and paste the new one)");
        } else if (code != null && code != CloseCode.GRACEFUL_CLOSE) {
            Discord.LOG.error("Discord connection closed: {}", code.getMeaning());
        }
    }

    // ------------------------------------------------------------------ messages

    @Override
    public void onMessageReceived(MessageReceivedEvent e) {
        if (!e.isFromGuild() || e.getAuthor().isBot() || e.isWebhookMessage() || e.getMember() == null) return;
        long ch = e.getChannel().getIdLong();
        Message msg = e.getMessage();
        String content = msg.getContentDisplay().trim();
        if (content.isEmpty() && msg.getAttachments().isEmpty() && msg.getStickers().isEmpty() && !warnedNoContent) {
            warnedNoContent = true;
            Discord.LOG.warn("Discord sent a message with no text; if every message shows up empty, turn on the Message Content Intent");
        }
        String name = e.getMember().getEffectiveName();

        if (ch == DiscordConfig.channel("console")) {
            if (content.isEmpty()) return;
            if (!hasCommandRole(e.getMember())) return;
            String command = content.startsWith("/") ? content.substring(1) : content;
            Discord.LOG.info("{} ran from the Discord console channel: /{}", name, command);
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
            msg.addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("✅")).queue(null, err -> { });
            return;
        }
        boolean chat = ch == DiscordConfig.channel("chat") && DiscordConfig.FROM_DISCORD.get();
        boolean staff = ch == DiscordConfig.channel("staff");
        if (!chat && !staff) return;

        List<String> parts = new ArrayList<>();
        if (!content.isEmpty()) parts.add(Discord.cut(content, DiscordConfig.MAX_LENGTH.get()));
        msg.getAttachments().forEach(a -> parts.add(a.getUrl()));
        if (parts.isEmpty()) return;
        String text = String.join(" ", parts);
        server.execute(() -> {
            MutableComponent line = Messages.get(staff ? "discord.staff.in" : "discord.chat.in",
                    Text.user(name, false, false, false, false), Text.user(text, false, false, false, true));
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!staff || Perms.has(p, "essentials.helpop.receive", Perms.OP)) p.sendSystemMessage(line);
            }
            server.sendSystemMessage(line);
        });
    }

    private static boolean hasCommandRole(Member m) {
        List<? extends String> roles = DiscordConfig.COMMAND_ROLES.get();
        if (roles.isEmpty()) return false;
        for (Role r : m.getUnsortedRoles()) {
            if (roles.contains(r.getId())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ slash commands

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        switch (e.getName()) {
            case "list" -> list(e);
            case "execute" -> execute(e);
            case "msg" -> msg(e);
            default -> { }
        }
    }

    private void list(SlashCommandInteractionEvent e) {
        e.deferReply().queue();
        server.execute(() -> {
            List<String> names = server.getPlayerList().getPlayers().stream()
                    .filter(p -> !Vanish.isVanished(p))
                    .map(p -> p.getGameProfile().getName())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
            String reply = names.isEmpty() ? Discord.fill("discord.list.empty")
                    : Discord.fill("discord.list", names.size(), server.getMaxPlayers(), Discord.escape(String.join(", ", names)));
            e.getHook().sendMessage(Discord.cut(reply, 2000)).queue();
        });
    }

    private void execute(SlashCommandInteractionEvent e) {
        if (e.getMember() == null || !hasCommandRole(e.getMember())) {
            e.reply(Discord.fill("discord.execute.denied")).setEphemeral(true).queue();
            return;
        }
        String raw = e.getOption("command", OptionMapping::getAsString);
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        String who = e.getMember().getEffectiveName();
        e.deferReply(true).queue();
        server.execute(() -> {
            Discord.LOG.info("{} ran from Discord: /{}", who, command);
            StringBuilder out = new StringBuilder();
            CommandSource capture = new CommandSource() {
                @Override
                public void sendSystemMessage(Component c) {
                    if (out.length() < 1900) out.append(c.getString()).append('\n');
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return true;
                }
            };
            ServerLevel level = server.overworld();
            CommandSourceStack source = new CommandSourceStack(capture, Vec3.atLowerCornerOf(level.getSharedSpawnPos()), Vec2.ZERO,
                    level, 4, "Discord (" + who + ")", Component.literal("Discord (" + who + ")"), server, null);
            server.getCommands().performPrefixedCommand(source, command);
            String result = out.length() == 0 ? Discord.fill("discord.execute.noOutput")
                    : "```\n" + Discord.cut(out.toString().replace("```", "`​``"), 1900) + "```";
            e.getHook().sendMessage(result).queue();
        });
    }

    private void msg(SlashCommandInteractionEvent e) {
        String target = e.getOption("player", OptionMapping::getAsString);
        String text = Discord.cut(e.getOption("message", OptionMapping::getAsString), DiscordConfig.MAX_LENGTH.get());
        String from = e.getMember() != null ? e.getMember().getEffectiveName() : e.getUser().getName();
        e.deferReply(true).queue();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayerByName(target);
            String reply;
            if (p == null || Vanish.isVanished(p)) {
                reply = Discord.fill("discord.msg.notOnline", Discord.escape(target));
            } else if (UserStore.get(p).msgDisabled) {
                reply = Discord.fill("discord.msg.disabled", Discord.escape(p.getGameProfile().getName()));
            } else {
                p.sendSystemMessage(Messages.get("discord.msg.in", Text.user(from, false, false, false, false),
                        Text.user(text, false, false, false, true)));
                server.sendSystemMessage(Component.literal("[Discord] " + from + " -> " + p.getGameProfile().getName() + ": " + text));
                reply = Discord.fill("discord.msg.sent", Discord.escape(p.getGameProfile().getName()));
            }
            e.getHook().sendMessage(reply).queue();
        });
    }
}
