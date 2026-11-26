package dev.curatorium.essentials.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.curatorium.essentials.perm.Perms;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * One Essentials command. Subclasses add their arguments in {@link #build}; wrap every
 * {@code executes} body in {@link #exec} so cooldowns and command costs apply.
 *
 * <p>Arguments must be vanilla argument types (strings, numbers, vanilla entity/position
 * arguments…), never custom ones: unmodded clients can't read custom argument types.
 */
public abstract class EssCommand {
    private final String name;
    private final List<String> aliases;
    private final String node;
    private final int defaultLevel;

    /**
     * @param name         main name, also used in config (disabled, cooldowns, costs)
     * @param defaultLevel who may use it without a LuckPerms node: {@link Perms#ALL}, {@link Perms#OP} or {@link Perms#ADMIN}
     * @param aliases      other names, registered as copies of the command
     */
    protected EssCommand(String name, int defaultLevel, String... aliases) {
        this.name = name.toLowerCase(Locale.ROOT);
        this.aliases = List.of(aliases);
        this.node = "essentials." + this.name;
        this.defaultLevel = defaultLevel;
    }

    public final String name() {
        return name;
    }

    public final List<String> aliases() {
        return aliases;
    }

    /** The node that lets someone use this command: {@code essentials.<name>}. */
    public final String node() {
        return node;
    }

    public final int defaultLevel() {
        return defaultLevel;
    }

    /** Adds arguments and {@code executes} to the command's root literal (already named and permission-checked). */
    protected abstract void build(LiteralArgumentBuilder<CommandSourceStack> root);

    /** Wraps a command body with cooldown and cost handling. */
    protected final Command<CommandSourceStack> exec(Command<CommandSourceStack> body) {
        return ctx -> CommandGuard.run(this, ctx, body);
    }

    /** Whether the source has {@code essentials.<name>.<sub>}, default the given level. */
    protected final boolean has(CommandSourceStack source, String sub, int defaultLevel) {
        return Perms.has(source, node + "." + sub, defaultLevel);
    }

    protected static void reply(CommandContext<CommandSourceStack> ctx, Component message) {
        ctx.getSource().sendSuccess(() -> message, false);
    }

    protected static int fail(CommandContext<CommandSourceStack> ctx, Component message) {
        ctx.getSource().sendFailure(message);
        return 0;
    }
}
