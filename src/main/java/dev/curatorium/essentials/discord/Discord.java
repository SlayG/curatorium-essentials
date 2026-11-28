package dev.curatorium.essentials.discord;

import dev.curatorium.essentials.CuratoriumEssentials;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.Text;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.attribute.IWebhookContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Discord bot: starts with the server, stops with it, and sends messages to the configured
 * channels. Everything here is safe to call when the bot is off; it then does nothing.
 *
 * <p>JDA runs on its own threads. Anything that touches the game goes through
 * {@code server.execute}.
 */
public final class Discord {
    /** Kept out of the console stream, so a failing send can't feed itself. */
    static final Logger LOG = LoggerFactory.getLogger("Curatorium Essentials/Discord");
    static final String WEBHOOK_NAME = "Curatorium Essentials";

    private static volatile JDA jda;
    private static volatile boolean ready;
    private static int startedWith;
    /** Messages sent before the bot finished logging in (the "server started" one, mostly). */
    private static final List<Runnable> pending = new ArrayList<>();
    private static final ConcurrentHashMap<Long, CompletableFuture<Webhook>> webhooks = new ConcurrentHashMap<>();
    private static ScheduledExecutorService timer;

    static {
        // Nobody can ping @everyone, roles or users through the bot.
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
    }

    public static boolean running() {
        return jda != null && ready;
    }

    static JDA jda() {
        return jda;
    }

    private static int fingerprint() {
        return Objects.hash(DiscordConfig.ENABLED.get(), DiscordConfig.token(), DiscordConfig.GUILD.get(), DiscordConfig.MEMBER_INTENT.get(),
                DiscordConfig.EMOJIS.get());
    }

    public static synchronized void start(MinecraftServer server) {
        startedWith = fingerprint();
        if (!DiscordConfig.ENABLED.get()) return;
        String token = DiscordConfig.token();
        if (token.isEmpty()) {
            LOG.warn("Discord is enabled but bot.token is empty in config/curatorium_essentials/discord.toml");
            return;
        }
        ready = false;
        webhooks.clear();
        try {
            EnumSet<GatewayIntent> intents = EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES);
            // Role-change events for role sync. Privileged: the developer portal must allow it.
            if (DiscordConfig.MEMBER_INTENT.get()) intents.add(GatewayIntent.GUILD_MEMBERS);
            JDABuilder builder = JDABuilder.createLight(token, intents);
            // Emoji add/remove/rename events and the guild's emoji list. Not a privileged intent.
            if (DiscordConfig.EMOJIS.get()) builder.enableIntents(GatewayIntent.GUILD_EXPRESSIONS).enableCache(CacheFlag.EMOJI);
            jda = builder.addEventListeners(new DiscordListener(server)).build();
        } catch (RuntimeException e) {
            // Thrown for a malformed token. Never log the token itself.
            LOG.error("Could not start the Discord bot: {}", e.getMessage());
            jda = null;
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Curatorium Discord timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(ConsoleStream::flush, 2, 2, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(new Runnable() {
            private int minutes;

            @Override
            public void run() {
                Links.expireCodes();
                int every = DiscordConfig.RESYNC_MINUTES.get();
                if (every <= 0 || ++minutes < every || !running()) return;
                minutes = 0;
                server.execute(() -> server.getPlayerList().getPlayers().forEach(p -> {
                    Links.Link link = Links.get(p.getUUID());
                    if (link != null) RoleSync.full(p.getUUID(), link.discordId(), p.getGameProfile().getName());
                }));
            }
        }, 1, 1, TimeUnit.MINUTES);
        ConsoleStream.install();
    }

    /** Called by the listener once logged in. */
    static void onReady() {
        List<Runnable> run;
        synchronized (pending) {
            ready = true;
            run = new ArrayList<>(pending);
            pending.clear();
        }
        run.forEach(Runnable::run);
    }

    /** Sends the stop message (waiting briefly for it) and logs out. */
    public static synchronized void stop(String stopMessage) {
        JDA j = jda;
        if (j == null) return;
        if (stopMessage != null && ready) {
            ConsoleStream.flush();
            long id = DiscordConfig.channel(DiscordConfig.types().getOrDefault("stop", "none"));
            GuildMessageChannel ch = id == 0 ? null : j.getChannelById(GuildMessageChannel.class, id);
            if (ch != null) {
                try {
                    ch.sendMessage(stopMessage).submit().get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOG.warn("Could not send the stop message: {}", e.toString());
                }
            }
        }
        ConsoleStream.uninstall();
        if (timer != null) timer.shutdownNow();
        timer = null;
        ready = false;
        jda = null;
        DiscordEmojis.clear();
        synchronized (pending) {
            pending.clear();
        }
        j.shutdown();
        try {
            if (!j.awaitShutdown(5, TimeUnit.SECONDS)) j.shutdownNow();
        } catch (InterruptedException e) {
            j.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** After a config reload: restarts the bot if it was turned on or off or its token changed. */
    public static void onConfigReload() {
        MinecraftServer server = CuratoriumEssentials.server();
        if (server == null || fingerprint() == startedWith) return;
        Thread t = new Thread(() -> {
            LOG.info("Discord settings changed; restarting the bot");
            stop(null);
            start(server);
        }, "Curatorium Discord restart");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ sending

    /** Runs {@code r} now if logged in, or once logged in. */
    private static void whenReady(Runnable r) {
        if (jda == null) return;
        synchronized (pending) {
            if (!ready) {
                if (pending.size() < 100) pending.add(r);
                return;
            }
        }
        r.run();
    }

    /** Sends a message of a type (see {@link DiscordConfig#DEFAULT_TYPES}) to the channel it's routed to. */
    public static void send(String type, String text) {
        if (jda == null) return;
        long id = DiscordConfig.channel(DiscordConfig.types().getOrDefault(type, "none"));
        if (id != 0) post(id, text);
    }

    /** Posts as the bot. False if the channel isn't known. */
    public static boolean post(long channelId, String text) {
        if (jda == null || text.isBlank()) return false;
        whenReady(() -> {
            GuildMessageChannel ch = jda.getChannelById(GuildMessageChannel.class, channelId);
            if (ch == null) {
                LOG.warn("Discord channel {} not found, or the bot can't see it", Long.toUnsignedString(channelId));
                return;
            }
            ch.sendMessage(cut(text, 2000)).queue(null, err -> LOG.warn("Could not send to #{}: {}", ch.getName(), err.getMessage()));
        });
        return true;
    }

    /** A player's chat line, through the webhook if there is one. */
    public static void chat(ServerPlayer player, String raw) {
        if (jda == null) return;
        long id = DiscordConfig.channel(DiscordConfig.types().getOrDefault("chat", "none"));
        if (id == 0) return;
        String name = player.getDisplayName().getString();
        String message = DiscordEmojis.toDiscord(Text.plain(raw));
        if (!DiscordConfig.WEBHOOK.get()) {
            post(id, fill("discord.out.chat", escape(name), message));
            return;
        }
        String avatar = DiscordConfig.AVATAR_URL.get()
                .replace("{name}", player.getGameProfile().getName())
                .replace("{uuid}", player.getUUID().toString());
        whenReady(() -> webhook(id).thenAccept(hook -> {
            if (hook == null) {
                post(id, fill("discord.out.chat", escape(name), message));
                return;
            }
            hook.sendMessage(cut(message, 2000)).setUsername(webhookName(name, player.getGameProfile().getName()))
                    .setAvatarUrl(avatar)
                    .queue(null, err -> {
                        LOG.warn("Webhook send failed ({}); posting as the bot", err.getMessage());
                        webhooks.remove(id);
                        post(id, fill("discord.out.chat", escape(name), message));
                    });
        }));
    }

    /** The mod's webhook in a channel, made if missing; completes with null if it can't have one. */
    private static CompletableFuture<Webhook> webhook(long channelId) {
        return webhooks.computeIfAbsent(channelId, id -> {
            CompletableFuture<Webhook> f = new CompletableFuture<>();
            GuildMessageChannel ch = jda.getChannelById(GuildMessageChannel.class, id);
            if (!(ch instanceof IWebhookContainer container)) {
                f.complete(null);
                return f;
            }
            long self = jda.getSelfUser().getIdLong();
            container.retrieveWebhooks().queue(list -> {
                for (Webhook w : list) {
                    if (WEBHOOK_NAME.equals(w.getName()) && w.getOwnerAsUser() != null
                            && w.getOwnerAsUser().getIdLong() == self && w.getToken() != null) {
                        f.complete(w);
                        return;
                    }
                }
                container.createWebhook(WEBHOOK_NAME).queue(f::complete, err -> {
                    LOG.warn("Could not create a webhook in #{} ({}); chat is posted by the bot instead", ch.getName(), err.getMessage());
                    f.complete(null);
                });
            }, err -> {
                LOG.warn("No Manage Webhooks permission in #{}; chat is posted by the bot instead", ch.getName());
                f.complete(null);
            });
            return f;
        });
    }

    /** Discord refuses webhook names containing "discord" or "clyde", and longer than 80. */
    private static String webhookName(String display, String username) {
        String n = display.isBlank() ? username : display;
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.contains("discord") || lower.contains("clyde")) n = username;
        lower = n.toLowerCase(Locale.ROOT);
        if (lower.contains("discord") || lower.contains("clyde")) n = n.replaceAll("(?i)discord", "Dis-cord").replaceAll("(?i)clyde", "Cly-de");
        return cut(n, 80);
    }

    public static void updateStatus(MinecraftServer server) {
        JDA j = jda;
        if (j == null) return;
        String s = DiscordConfig.STATUS.get();
        if (s.isBlank()) {
            j.getPresence().setActivity(null);
            return;
        }
        long online = server.getPlayerList().getPlayers().stream().filter(p -> !dev.curatorium.essentials.player.Vanish.isVanished(p)).count();
        j.getPresence().setActivity(Activity.customStatus(cut(s.replace("{online}", Long.toString(online))
                .replace("{max}", Integer.toString(server.getMaxPlayers())), 128)));
    }

    // ------------------------------------------------------------------ text

    /** A Discord-side message from messages.properties, with {0}, {1}… filled and colour codes removed. */
    public static String fill(String key, Object... args) {
        String s = Text.plain(Messages.raw(key));
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    /** Escapes Discord markdown in text a player wrote. */
    public static String escape(String s) {
        return MarkdownSanitizer.escape(s);
    }

    static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private Discord() {}
}
