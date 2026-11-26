package dev.curatorium.essentials.discord;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Streams the server log to the console channel: lines are collected and posted every two
 * seconds in code blocks. JDA's own log lines and the bot's are skipped, so a failing send
 * can't loop.
 */
final class ConsoleStream extends AbstractAppender {
    private static final int MAX_QUEUED = 2000;
    private static final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger queued = new AtomicInteger();
    private static final AtomicInteger dropped = new AtomicInteger();
    private static ConsoleStream installed;

    private ConsoleStream() {
        super("CuratoriumEssentialsDiscordConsole", null,
                PatternLayout.newBuilder().withPattern("[%d{HH:mm:ss} %level] [%logger{1}]: %msg%n").build(),
                false, Property.EMPTY_ARRAY);
    }

    static synchronized void install() {
        if (installed != null || DiscordConfig.channel("console") == 0) return;
        installed = new ConsoleStream();
        installed.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getRootLogger().addAppender(installed, null, null);
        ctx.updateLoggers();
    }

    static synchronized void uninstall() {
        if (installed == null) return;
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getRootLogger().removeAppender(installed.getName());
        ctx.updateLoggers();
        installed.stop();
        installed = null;
        lines.clear();
        queued.set(0);
    }

    @Override
    public void append(LogEvent e) {
        String logger = e.getLoggerName() == null ? "" : e.getLoggerName();
        if (logger.startsWith("net.dv8tion") || logger.startsWith("dev.curatorium.essentials.lib")
                || logger.equals(Discord.LOG.getName()) || logger.startsWith("JDA")) return;
        if (!e.getLevel().isMoreSpecificThan(Level.toLevel(DiscordConfig.CONSOLE_LEVEL.get(), Level.INFO))) return;
        if (queued.get() >= MAX_QUEUED) {
            dropped.incrementAndGet();
            return;
        }
        String line = getLayout().toSerializable(e).toString()
                .replaceAll("\u001B\\[[;\\d]*m", "")
                .replaceAll("§.", "")
                .replace("```", "`​``");
        lines.add(line);
        queued.incrementAndGet();
    }

    /** Posts what has been collected, in as few messages as fit. */
    static void flush() {
        long channel = DiscordConfig.channel("console");
        if (channel == 0 || !Discord.running()) return;
        StringBuilder chunk = new StringBuilder();
        int skipped = dropped.getAndSet(0);
        if (skipped > 0) chunk.append("(").append(skipped).append(" log lines skipped)\n");
        String line;
        int messages = 0;
        while ((line = lines.poll()) != null) {
            queued.decrementAndGet();
            if (line.length() > 1900) line = line.substring(0, 1900) + "…\n";
            if (chunk.length() + line.length() > 1900) {
                Discord.post(channel, "```\n" + chunk + "```");
                chunk.setLength(0);
                chunk.append(line);
                // Stay well under Discord's rate limit; the rest goes in the next round.
                if (++messages >= 4) break;
                continue;
            }
            chunk.append(line);
        }
        if (!chunk.isEmpty()) Discord.post(channel, "```\n" + chunk + "```");
    }
}
