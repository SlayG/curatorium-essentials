package dev.curatorium.essentials.economy;

import dev.curatorium.essentials.CuratoriumEssentials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/**
 * A player's money: the Numismatics coins they carry. There are no stored balances. Every
 * price is taken from the inventory and every payout given into it. Every change is logged to
 * {@code logs/curatorium_essentials-economy.log}.
 *
 * <p>Without Numismatics installed, the economy is off: nobody has money, and anything with a
 * price can't be paid for.
 */
public final class Wallet {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static Boolean available;

    public static boolean available() {
        if (available == null) available = ModList.get().isLoaded("numismatics");
        return available;
    }

    /** Total value carried, in spurs. */
    public static long balance(ServerPlayer player) {
        return available() ? NumismaticsCoins.count(player) : 0;
    }

    public static boolean canAfford(ServerPlayer player, long spurs) {
        return spurs <= 0 || balance(player) >= spurs;
    }

    /**
     * Takes {@code spurs} worth of coins, giving change. Changes nothing and returns false if
     * the player can't afford it.
     */
    public static boolean take(ServerPlayer player, long spurs, String reason) {
        if (spurs <= 0) return true;
        if (!available() || !NumismaticsCoins.take(player, spurs)) return false;
        log(player, -spurs, reason);
        return true;
    }

    /** Gives {@code spurs} worth of coins in the fewest coins; what doesn't fit drops at the player's feet. */
    public static void give(ServerPlayer player, long spurs, String reason) {
        if (spurs <= 0 || !available()) return;
        NumismaticsCoins.give(player, spurs);
        log(player, spurs, reason);
    }

    private static void log(ServerPlayer player, long change, String reason) {
        String line = String.format("%s %s (%s) %s%d spurs: %s%n", LocalDateTime.now().format(TIME),
                player.getGameProfile().getName(), player.getUUID(), change >= 0 ? "+" : "", change, reason);
        Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("curatorium_essentials-economy.log");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            CuratoriumEssentials.LOGGER.warn("Could not write the economy log: {}", line.trim(), e);
        }
    }

    private Wallet() {}
}
