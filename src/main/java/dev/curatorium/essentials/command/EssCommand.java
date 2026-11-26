package dev.curatorium.essentials.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

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
        this(name, "essentials." + name.toLowerCase(Locale.ROOT), defaultLevel, aliases);
    }

    /** A command whose permission node isn't {@code essentials.<name>} (e.g. /gmc uses essentials.gamemode.creative). */
    protected EssCommand(String name, String node, int defaultLevel, String... aliases) {
        this.name = name.toLowerCase(Locale.ROOT);
        this.aliases = List.of(aliases);
        this.node = node;
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

    /** An error that stops the command and shows {@code message} in red. */
    protected static CommandSyntaxException error(Component message) {
        return new SimpleCommandExceptionType(message).create();
    }

    /** A player-name argument (plain word, so partial names, nicknames and offline players work). */
    protected static RequiredArgumentBuilder<CommandSourceStack, String> playerArg(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests(PlayerLookup.ONLINE);
    }

    /** A greedy text argument: everything to the end of the line. */
    protected static RequiredArgumentBuilder<CommandSourceStack, String> textArg(String name) {
        return Commands.argument(name, StringArgumentType.greedyString());
    }

    /** The online player named by argument {@code name}, or an error. Vanished players count as offline unless the source can see them. */
    protected static ServerPlayer online(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return online(ctx.getSource(), StringArgumentType.getString(ctx, name));
    }

    /** The online player named {@code input}, as {@link #online(CommandContext, String)}. */
    protected static ServerPlayer online(CommandSourceStack source, String input) throws CommandSyntaxException {
        return PlayerLookup.online(source, input).orElseThrow(() -> error(Messages.get("player.notOnline", input)));
    }

    /** The command's build context (registries for item, enchantment and entity arguments); set while commands register. */
    protected static CommandBuildContext buildContext() {
        return CommandRegistry.buildContext();
    }

    /** A command body that acts on one player. */
    @FunctionalInterface
    protected interface PlayerAction {
        int run(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException;
    }

    /**
     * {@code /cmd} acts on the sender, {@code /cmd <player>} on someone else (needs
     * {@code essentials.<name>.others}, default op).
     */
    protected final void selfOrOther(LiteralArgumentBuilder<CommandSourceStack> root, PlayerAction action) {
        root.executes(exec(ctx -> action.run(ctx, self(ctx))))
                .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                        .executes(exec(ctx -> action.run(ctx, online(ctx, "player")))));
    }

    /** Tells the sender about something done to another player (nothing if they did it to themselves). */
    protected static void tellOther(CommandContext<CommandSourceStack> ctx, ServerPlayer target, Component message) {
        if (ctx.getSource().getPlayer() != target) reply(ctx, message);
    }

    /** Any known player named by argument {@code name}, online or not, or an error. */
    protected static PlayerLookup.Target known(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        String input = StringArgumentType.getString(ctx, name);
        return PlayerLookup.any(ctx.getSource().getServer(), input)
                .orElseThrow(() -> error(Messages.get("player.notFound", input)));
    }

    protected static String str(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    /** The player running the command, or an error if it's the console. */
    protected static ServerPlayer self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) throw error(Messages.get("command.playerOnly"));
        return p;
    }
}
