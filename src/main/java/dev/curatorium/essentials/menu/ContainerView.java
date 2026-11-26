package dev.curatorium.essentials.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Passes everything to another container except opening and closing, so viewing someone's
 * ender chest (/enderchest) doesn't disturb the ender chest block they may have open.
 */
public final class ContainerView implements Container {
    private final Container inner;
    private final java.util.function.Predicate<Player> valid;

    public ContainerView(Container inner, java.util.function.Predicate<Player> valid) {
        this.inner = inner;
        this.valid = valid;
    }

    @Override public int getContainerSize() { return inner.getContainerSize(); }
    @Override public boolean isEmpty() { return inner.isEmpty(); }
    @Override public ItemStack getItem(int slot) { return inner.getItem(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { return inner.removeItem(slot, amount); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return inner.removeItemNoUpdate(slot); }
    @Override public void setItem(int slot, ItemStack stack) { inner.setItem(slot, stack); }
    @Override public void setChanged() { inner.setChanged(); }
    @Override public boolean stillValid(Player player) { return valid.test(player); }
    @Override public void clearContent() { inner.clearContent(); }
}
