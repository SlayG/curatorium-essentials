package dev.curatorium.essentials.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.perm.Perms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

/**
 * Portable workstations: the vanilla menus, opened without the block. Each menu is told it is
 * at the player's position (so items left in it go back to the player when it closes) and never
 * closes for being too far from a block.
 */
public final class WorkstationCommands {
    @FunctionalInterface
    interface MenuFactory {
        AbstractContainerMenu create(int id, Inventory inv, ContainerLevelAccess access);
    }

    public static void register() {
        add("workbench", "container.crafting", (id, inv, a) -> new CraftingMenu(id, inv, a) {
            @Override public boolean stillValid(Player p) { return true; }
        }, "eworkbench", "wb", "ewb", "wbench", "ewbench", "craft", "ecraft");
        add("anvil", "container.repair", (id, inv, a) -> new AnvilMenu(id, inv, a) {
            @Override public boolean stillValid(Player p) { return true; }
        }, "eanvil");
        add("cartographytable", "container.cartography_table", (id, inv, a) -> new CartographyTableMenu(id, inv, a) {
            @Override public boolean stillValid(Player p) { return true; }
        }, "ecartographytable", "carttable", "ecarttable");
        add("grindstone", "container.grindstone_title", (id, inv, a) -> new GrindstoneMenu(id, inv, a) {
            @Override public boolean stillValid(Player p) { return true; }
        }, "egrindstone", "gstone", "egstone");
        add("loom", "container.loom", (id, inv, a) -> new LoomMenu(id, inv, a) {
            @Override public boolean stillValid(Player p) { return true; }
        }, "eloom");
        add("smithingtable", "container.upgrade", (id, inv, a) -> new SmithingMenu(id, inv, a) {
            @Override public boolean stillValid(Player p) { return true; }
        }, "esmithingtable", "smithing", "esmithing");
        add("stonecutter", "container.stonecutter", (id, inv, a) -> new StonecutterMenu(id, inv, a) {
            @Override public boolean stillValid(Player p) { return true; }
        }, "estonecutter");
    }

    private static void add(String name, String titleKey, MenuFactory factory, String... aliases) {
        CommandRegistry.add(new Workstation(name, titleKey, factory, aliases));
    }

    static final class Workstation extends EssCommand {
        private final String titleKey;
        private final MenuFactory factory;

        Workstation(String name, String titleKey, MenuFactory factory, String... aliases) {
            super(name, Perms.OP, aliases);
            this.titleKey = titleKey;
            this.factory = factory;
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                ContainerLevelAccess access = ContainerLevelAccess.create(me.level(), me.blockPosition());
                me.openMenu(new SimpleMenuProvider((id, inv, p) -> factory.create(id, inv, access), Component.translatable(titleKey)));
                return 1;
            }));
        }
    }

    private WorkstationCommands() {}
}
