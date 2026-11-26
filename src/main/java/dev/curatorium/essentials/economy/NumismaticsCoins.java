package dev.curatorium.essentials.economy;

import dev.curatorium.essentials.config.EssConfig;
import dev.ithundxr.createnumismatics.content.backend.Coin;
import dev.ithundxr.createnumismatics.content.coins.CoinItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Numismatics coin items in a player's inventory (main inventory, offhand and, if
 * {@code economy.countEnderChest} is on, the ender chest). Only {@link Wallet} calls this, and
 * only when Numismatics is installed. Works like Curatorium Exchange's coin handling.
 */
final class NumismaticsCoins {
    static long count(ServerPlayer player) {
        long total = 0;
        for (Slot slot : slots(player)) total += valueOf(slot.stack());
        return total;
    }

    /** Spends the smallest coins first so players keep their big ones, then gives change. */
    static boolean take(ServerPlayer player, long amount) {
        if (count(player) < amount) return false;
        List<Slot> slots = slots(player);
        long taken = 0;
        for (Coin coin : Coin.values()) {
            for (Slot slot : slots) {
                if (taken >= amount) break;
                ItemStack s = slot.stack();
                if (!(s.getItem() instanceof CoinItem c) || c.coin != coin) continue;
                long need = amount - taken;
                int use = (int) Math.min(s.getCount(), (need + coin.value - 1) / coin.value);
                s.shrink(use);
                taken += (long) use * coin.value;
                slot.container().setChanged();
            }
        }
        if (taken > amount) give(player, taken - amount);
        return true;
    }

    static void give(ServerPlayer player, long amount) {
        while (amount > 0) {
            int chunk = (int) Math.min(amount, Integer.MAX_VALUE / 2);
            for (Map.Entry<Coin, Integer> e : Coin.getCoinsFromSpurAmount(chunk)) {
                int n = e.getValue();
                while (n > 0) {
                    ItemStack stack = e.getKey().asStack(1);
                    int count = Math.min(n, stack.getMaxStackSize());
                    stack.setCount(count);
                    n -= count;
                    if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false);
                }
            }
            amount -= chunk;
        }
        player.getInventory().setChanged();
    }

    private static long valueOf(ItemStack stack) {
        return stack.getItem() instanceof CoinItem c ? (long) c.coin.value * stack.getCount() : 0;
    }

    private record Slot(Container container, ItemStack stack) {}

    private static List<Slot> slots(ServerPlayer player) {
        List<Slot> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (ItemStack s : inv.items) out.add(new Slot(inv, s));
        for (ItemStack s : inv.offhand) out.add(new Slot(inv, s));
        if (EssConfig.countEnderChest()) {
            Container ender = player.getEnderChestInventory();
            for (int i = 0; i < ender.getContainerSize(); i++) out.add(new Slot(ender, ender.getItem(i)));
        }
        return out;
    }

    private NumismaticsCoins() {}
}
