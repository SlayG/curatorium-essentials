package dev.curatorium.essentials.config;

import dev.curatorium.essentials.CuratoriumEssentials;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/curatorium_essentials/config.toml}. NeoForge reloads it when the file changes;
 * {@link #bake()} turns the raw values into the lookups the rest of the mod reads.
 */
public final class EssConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> OVERRIDE_VANILLA;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> COOLDOWNS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> COSTS;
    private static final ModConfigSpec.BooleanValue COUNT_ENDER_CHEST;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Which commands exist and how they behave. Use each command's main name, not an alias.")
                .push("commands");
        DISABLED = b.comment("Commands that are not registered at all, e.g. [\"nuke\", \"kittycannon\"].",
                        "Changing this needs a server restart.")
                .defineListAllowEmpty("disabled", List.of(), EssConfig::isString);
        OVERRIDE_VANILLA = b.comment("Vanilla commands that Essentials replaces with its own version, when it has one.",
                        "Remove a name to keep the vanilla command. Changing this needs a server restart.")
                .defineListAllowEmpty("overrideVanilla", List.of(
                        "msg", "tell", "w", "me", "tp", "teleport", "give", "kick", "ban", "ban-ip",
                        "pardon", "pardon-ip", "time", "weather", "gamemode", "kill", "list", "help"),
                        EssConfig::isString);
        COOLDOWNS = b.comment("Per-command cooldowns as \"command=seconds\", e.g. [\"heal=60\"].",
                        "Bypass with essentials.commandcooldowns.bypass (or essentials.commandcooldowns.bypass.<command>).")
                .defineListAllowEmpty("cooldowns", List.of(), EssConfig::isString);
        COSTS = b.comment("Per-command prices in spurs, paid with Numismatics coins from the player's inventory,",
                        "as \"command=spurs\", e.g. [\"repair=64\"]. Bypass with essentials.nocommandcost.<command>",
                        "or essentials.nocommandcost.all.")
                .defineListAllowEmpty("costs", List.of(), EssConfig::isString);
        b.pop();

        b.comment("Money is the Numismatics coins players carry. Nothing is stored as a virtual balance.")
                .push("economy");
        COUNT_ENDER_CHEST = b.comment("Also count and spend coins in the player's ender chest.")
                .define("countEnderChest", false);
        b.pop();

        SPEC = b.build();
    }

    private static Set<String> disabled = Set.of();
    private static Set<String> overrideVanilla = Set.of();
    private static Map<String, Long> cooldowns = Map.of();
    private static Map<String, Long> costs = Map.of();
    private static boolean countEnderChest;

    private static boolean isString(Object o) {
        return o instanceof String;
    }

    public static void bake() {
        if (!SPEC.isLoaded()) return;
        disabled = lower(DISABLED.get());
        overrideVanilla = lower(OVERRIDE_VANILLA.get());
        cooldowns = pairs(COOLDOWNS.get(), "cooldowns");
        costs = pairs(COSTS.get(), "costs");
        countEnderChest = COUNT_ENDER_CHEST.get();
    }

    private static Set<String> lower(List<? extends String> list) {
        Set<String> out = new HashSet<>();
        for (String s : list) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static Map<String, Long> pairs(List<? extends String> list, String what) {
        Map<String, Long> out = new HashMap<>();
        for (String entry : list) {
            int eq = entry.indexOf('=');
            try {
                if (eq < 1) throw new NumberFormatException();
                long value = Long.parseLong(entry.substring(eq + 1).trim());
                if (value < 0) throw new NumberFormatException();
                out.put(entry.substring(0, eq).trim().toLowerCase(Locale.ROOT), value);
            } catch (NumberFormatException e) {
                CuratoriumEssentials.LOGGER.warn("Ignoring bad commands.{} entry \"{}\" (expected command=number)", what, entry);
            }
        }
        return out;
    }

    public static boolean isDisabled(String command) {
        return disabled.contains(command);
    }

    public static boolean overridesVanilla(String label) {
        return overrideVanilla.contains(label);
    }

    /** Cooldown in seconds, 0 if none. */
    public static long cooldown(String command) {
        return cooldowns.getOrDefault(command, 0L);
    }

    /** Price in spurs, 0 if free. */
    public static long cost(String command) {
        return costs.getOrDefault(command, 0L);
    }

    public static boolean countEnderChest() {
        return countEnderChest;
    }

    private EssConfig() {}
}
