package dev.curatorium.essentials.menu;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Another player's inventory laid out as a 5-row chest for /invsee: rows 1-3 the main
 * inventory, row 4 the hotbar, row 5 boots, leggings, chestplate, helmet and the offhand (the
 * last four slots are unused). Reads and writes go straight to the live inventory.
 */
public final class InventoryView implements Container {
    public static final int SIZE = 45;
    public static final int USED = 41;
    private final ServerPlayer target;
    private final Inventory inv;

    public InventoryView(ServerPlayer target) {
        this.target = target;
        this.inv = target.getInventory();
    }

    /** Chest slot -> player inventory slot, or -1 for the unused ones. */
    private static int map(int slot) {
        if (slot < 27) return slot + 9;
        if (slot < 36) return slot - 27;
        if (slot < USED) return slot; // 36-39 armour, 40 offhand: same numbers in Inventory
        return -1;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        return inv.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        int i = map(slot);
        return i < 0 ? ItemStack.EMPTY : inv.getItem(i);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        int i = map(slot);
        return i < 0 ? ItemStack.EMPTY : inv.removeItem(i, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        int i = map(slot);
        return i < 0 ? ItemStack.EMPTY : inv.removeItemNoUpdate(i);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        int i = map(slot);
        if (i >= 0) inv.setItem(i, stack);
    }

    @Override
    public void setChanged() {
        inv.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return !target.isRemoved() && !target.hasDisconnected();
    }

    @Override
    public void clearContent() {
        // never clear someone's inventory from a GUI
    }
}
