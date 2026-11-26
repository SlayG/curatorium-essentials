package dev.curatorium.essentials.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import dev.curatorium.essentials.CuratoriumEssentials;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.perm.Perms;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Registers every {@link EssCommand}. Each name and alias becomes its own copy of the command
 * (Brigadier redirects don't run commands that take no arguments).
 *
 * <p>When a name is already taken: a vanilla command listed in {@code commands.overrideVanilla}
 * is replaced; anything else (another mod's command, or vanilla not on the list) is kept and
 * Essentials' version is skipped for that name, with a log line.
 */
public final class CommandRegistry {
    private static final List<EssCommand> COMMANDS = new ArrayList<>();
    private static final Map<String, EssCommand> LABELS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void add(EssCommand command) {
        COMMANDS.add(command);
    }

    public static List<EssCommand> all() {
        return Collections.unmodifiableList(COMMANDS);
    }

    /** The Essentials command registered under a label (name or alias), or null. */
    public static EssCommand byLabel(String label) {
        return LABELS.get(label);
    }

    public static void registerAll(CommandDispatcher<CommandSourceStack> dispatcher) {
        LABELS.clear();
        int count = 0;
        for (EssCommand command : COMMANDS) {
            if (EssConfig.isDisabled(command.name())) continue;
            List<String> labels = new ArrayList<>();
            labels.add(command.name());
            labels.addAll(command.aliases());
            for (String label : labels) {
                if (dispatcher.getRoot().getChild(label) != null) {
                    if (!EssConfig.overridesVanilla(label) || !remove(dispatcher.getRoot(), label)) {
                        CuratoriumEssentials.LOGGER.info("/{} already exists; keeping it instead of Essentials' version", label);
                        continue;
                    }
                }
                LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(label)
                        .requires(src -> Perms.has(src, command.node(), command.defaultLevel()));
                command.build(root);
                dispatcher.register(root);
                LABELS.put(label, command);
                count++;
            }
        }
        CuratoriumEssentials.LOGGER.info("Registered {} commands and aliases", count);
    }

    /** Takes a command out of the tree. Brigadier has no API for this, so it's done by reflection. */
    @SuppressWarnings("unchecked")
    private static boolean remove(RootCommandNode<CommandSourceStack> root, String label) {
        try {
            for (String fieldName : new String[] {"children", "literals", "arguments"}) {
                Field f = CommandNode.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                ((Map<String, ?>) f.get(root)).remove(label);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            CuratoriumEssentials.LOGGER.warn("Could not replace vanilla /{}", label, e);
            return false;
        }
    }

    private CommandRegistry() {}
}
