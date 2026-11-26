package dev.curatorium.essentials.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Small item helpers. */
public final class Items {
    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String id(ItemStack stack) {
        return id(stack.getItem());
    }

    /**
     * Gives {@code count} of {@code template} in full stacks, dropping at the player's feet what
     * doesn't fit. Returns how many went into the inventory.
     */
    public static int give(ServerPlayer player, ItemStack template, int count) {
        int max = Math.max(1, template.getMaxStackSize());
        int added = 0;
        while (count > 0) {
            int n = Math.min(max, count);
            count -= n;
            ItemStack stack = template.copyWithCount(n);
            player.getInventory().add(stack);
            added += n - stack.getCount();
            if (!stack.isEmpty()) player.drop(stack, false);
        }
        player.containerMenu.broadcastChanges();
        return added;
    }

    private Items() {}
}
