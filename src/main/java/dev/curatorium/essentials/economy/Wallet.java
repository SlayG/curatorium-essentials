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

    /** Whether the stack is a Numismatics coin (never sold, priced or put in a shop). */
    public static boolean isCoin(net.minecraft.world.item.ItemStack stack) {
        return available() && NumismaticsCoins.isCoin(stack);
    }

    /**
     * Holds coins for a player who is offline; they get them at their next login. The coins
     * are already taken from whoever paid, so this only records what's owed.
     */
    public static void escrow(java.util.UUID id, String name, long spurs, String reason) {
        if (spurs <= 0) return;
        var d = dev.curatorium.essentials.data.UserStore.get(id);
        d.escrow = Math.addExact(d.escrow, spurs);
        d.escrowNotes.add(reason);
        d.markDirty();
        writeLog(String.format("%s %s (%s) owed %d spurs, held until next login: %s%n", LocalDateTime.now().format(TIME), name, id, spurs, reason));
    }

    /** At login: hands over any coins held for the player. Returns the amount (0 if none). */
    public static long releaseEscrow(ServerPlayer player) {
        var d = dev.curatorium.essentials.data.UserStore.get(player);
        if (d.escrow <= 0 || !available()) return 0;
        long amount = d.escrow;
        d.escrow = 0;
        d.escrowNotes.clear();
        d.markDirty();
        NumismaticsCoins.give(player, amount);
        log(player.getGameProfile().getName(), player.getUUID(), amount, "coins held while offline");
        return amount;
    }

    private static void log(ServerPlayer player, long change, String reason) {
        log(player.getGameProfile().getName(), player.getUUID(), change, reason);
    }

    private static void log(String name, java.util.UUID id, long change, String reason) {
        writeLog(String.format("%s %s (%s) %s%d spurs: %s%n", LocalDateTime.now().format(TIME),
                name, id, change >= 0 ? "+" : "", change, reason));
    }

    private static void writeLog(String line) {
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
