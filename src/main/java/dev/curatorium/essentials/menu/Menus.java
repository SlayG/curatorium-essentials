package dev.curatorium.essentials.menu;

import java.util.function.IntConsumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * GUIs built from vanilla chest menus, so clients without the mod can open them. Every GUI is
 * server-side only: the client just sees a chest with a title.
 */
public final class Menus {
    public static MenuType<ChestMenu> chestType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /**
     * A chest nobody can take from or put into. {@code onClick} (may be null) gets the index of
     * a clicked slot in the chest part; the menu stays open unless it closes itself.
     */
    public static void openView(ServerPlayer player, Component title, Container contents, IntConsumer onClick) {
        int rows = Math.max(1, Math.min(6, (contents.getContainerSize() + 8) / 9));
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ViewMenu(chestType(rows), id, inv, contents, rows, onClick), title));
    }

    /** A container of {@code rows} rows filled from {@code items} (extra slots stay empty). */
    public static SimpleContainer container(int rows, java.util.List<ItemStack> items) {
        SimpleContainer c = new SimpleContainer(rows * 9);
        for (int i = 0; i < items.size() && i < c.getContainerSize(); i++) c.setItem(i, items.get(i));
        return c;
    }

    /** Read-only chest menu. */
    static final class ViewMenu extends ChestMenu {
        private final IntConsumer onClick;
        private final int size;

        ViewMenu(MenuType<?> type, int id, Inventory inv, Container contents, int rows, IntConsumer onClick) {
            super(type, id, inv, contents, rows);
            this.onClick = onClick;
            this.size = rows * 9;
        }

        @Override
        public void clicked(int slot, int button, ClickType type, Player player) {
            if (onClick != null && slot >= 0 && slot < size && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) {
                onClick.accept(slot);
            }
            // Undo whatever the client predicted.
            sendAllDataToRemote();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    /** A slot that can't be used: filler in GUIs like /invsee. */
    public static final class LockedSlot extends Slot {
        public LockedSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    /** Swaps slot {@code index} of a menu for a locked one. */
    public static void lock(ChestMenu menu, int index) {
        Slot old = menu.slots.get(index);
        Slot locked = new LockedSlot(old.container, old.getContainerSlot(), old.x, old.y);
        locked.index = index;
        menu.slots.set(index, locked);
    }

    private Menus() {}
}
