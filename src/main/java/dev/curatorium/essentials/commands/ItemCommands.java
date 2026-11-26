package dev.curatorium.essentials.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.menu.ContainerView;
import dev.curatorium.essentials.menu.InventoryView;
import dev.curatorium.essentials.menu.Menus;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.Text;
import dev.curatorium.essentials.util.Items;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;

/** Inventories, items, power tools and signs. */
public final class ItemCommands {
    public static void register() {
        CommandRegistry.add(new Invsee());
        CommandRegistry.add(new EnderChest());
        CommandRegistry.add(new ClearInventory());
        CommandRegistry.add(new ClearConfirmToggle());
        CommandRegistry.add(new Give());
        CommandRegistry.add(new ItemCommand());
        CommandRegistry.add(new More());
        CommandRegistry.add(new ItemDb());
        CommandRegistry.add(new ItemName());
        CommandRegistry.add(new ItemLoreCommand());
        CommandRegistry.add(new Enchant());
        CommandRegistry.add(new Repair());
        CommandRegistry.add(new Hat());
        CommandRegistry.add(new Condense());
        CommandRegistry.add(new Skull());
        CommandRegistry.add(new Book());
        CommandRegistry.add(new RecipeCommand());
        CommandRegistry.add(new Disposal());
        CommandRegistry.add(new EditSign());
    }

    public static void registerEvents(IEventBus bus) {
    }

    /** After /essentials reload: recipes may have changed. */
    public static void onReload() {
        Condense.clearCache();
    }

    static ItemStack held(ServerPlayer p) throws CommandSyntaxException {
        ItemStack stack = p.getMainHandItem();
        if (stack.isEmpty()) throw EssCommandErrors.error("item.holdSomething");
        return stack;
    }

    /** Lets static helpers throw the same red errors commands do. */
    static final class EssCommandErrors {
        static CommandSyntaxException error(String key, Object... args) {
            return new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Messages.get(key, args)).create();
        }
    }

    static Component itemName(ItemStack stack) {
        return stack.getHoverName();
    }

    // ---------------------------------------------------------------- invsee, enderchest, disposal

    /** /invsee <player>: live view of their inventory; changing it needs essentials.invsee.modify. */
    static final class Invsee extends EssCommand {
        Invsee() {
            super("invsee", Perms.OP, "einvsee");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                ServerPlayer target = online(ctx, "player");
                if (target == me) return fail(ctx, Messages.get("invsee.self"));
                boolean modify = has(ctx.getSource(), "modify", Perms.OP)
                        && !Perms.has(target, "essentials.invsee.preventmodify", Perms.ADMIN);
                InventoryView view = new InventoryView(target);
                Component title = Messages.get("invsee.title", target.getDisplayName());
                if (!modify) {
                    Menus.openView(me, title, view, null);
                } else {
                    me.openMenu(new SimpleMenuProvider((id, inv, p) -> {
                        ChestMenu menu = new ChestMenu(MenuType.GENERIC_9x5, id, inv, view, 5);
                        for (int i = InventoryView.USED; i < InventoryView.SIZE; i++) Menus.lock(menu, i);
                        return menu;
                    }, title));
                }
                return 1;
            })));
        }
    }

    /** /enderchest [player] */
    static final class EnderChest extends EssCommand {
        EnderChest() {
            super("enderchest", Perms.OP, "echest", "eechest", "eenderchest", "endersee", "eendersee", "ec", "eec");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> open(ctx, self(ctx))))
                    .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                            .executes(exec(ctx -> open(ctx, online(ctx, "player")))));
        }

        private int open(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            ContainerView view = new ContainerView(target.getEnderChestInventory(), p -> !target.hasDisconnected());
            Component title = target == me ? Component.translatable("container.enderchest")
                    : Messages.get("enderchest.title", target.getDisplayName());
            if (target != me && !has(ctx.getSource(), "modify", Perms.OP)) {
                Menus.openView(me, title, view, null);
            } else {
                me.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, view), title));
            }
            return 1;
        }
    }

    /** /disposal: a chest that destroys whatever is left in it when closed. */
    static final class Disposal extends EssCommand {
        Disposal() {
            super("disposal", Perms.OP, "edisposal", "trash", "etrash");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                self(ctx).openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, new SimpleContainer(36), 4),
                        Messages.get("disposal.title")));
                return 1;
            }));
        }
    }

    // ---------------------------------------------------------------- clearinventory

    /**
     * /clearinventory [player|*] [item] [amount]: clears the main inventory and offhand (not
     * armour). With confirmation on, the first run only asks to run it again within 30 seconds.
     */
    static final class ClearInventory extends EssCommand {
        private record Pending(String input, long until) {}

        private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

        ClearInventory() {
            super("clearinventory", Perms.OP, "ci", "eci", "clearinvent", "eclearinvent", "clean", "eclean", "clear", "eclear",
                    "eclearinventory");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> clear(ctx, null, null, -1)))
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(StateCommands.onlineAnd(c.getSource(), "*"), b))
                            .executes(exec(ctx -> clear(ctx, str(ctx, "player"), null, -1)))
                            .then(Commands.argument("item", ItemArgument.item(buildContext()))
                                    .executes(exec(ctx -> clear(ctx, str(ctx, "player"), ItemArgument.getItem(ctx, "item"), -1)))
                                    .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                            .executes(exec(ctx -> clear(ctx, str(ctx, "player"), ItemArgument.getItem(ctx, "item"),
                                                    IntegerArgumentType.getInteger(ctx, "amount")))))));
        }

        private int clear(CommandContext<CommandSourceStack> ctx, String who, ItemInput item, int amount) throws CommandSyntaxException {
            CommandSourceStack src = ctx.getSource();
            List<ServerPlayer> targets = new ArrayList<>();
            if (who == null) {
                targets.add(self(ctx));
            } else if (who.equals("*")) {
                if (!has(src, "all", Perms.ADMIN)) return fail(ctx, Messages.get("command.noPermission"));
                targets.addAll(src.getServer().getPlayerList().getPlayers());
            } else {
                ServerPlayer t = online(ctx, "player");
                if (t != src.getPlayer() && !has(src, "others", Perms.OP)) return fail(ctx, Messages.get("command.noPermission"));
                targets.add(t);
            }
            ServerPlayer me = src.getPlayer();
            if (me != null && confirmOn(me)) {
                String input = ctx.getInput();
                long now = System.currentTimeMillis();
                Pending p = pending.get(me.getUUID());
                if (p == null || !p.input().equals(input) || p.until() < now) {
                    pending.put(me.getUUID(), new Pending(input, now + 30_000));
                    reply(ctx, Messages.get("clearinventory.confirm", "/" + input));
                    return 0;
                }
                pending.remove(me.getUUID());
            }
            int total = 0;
            for (ServerPlayer t : targets) {
                Inventory inv = t.getInventory();
                int removed = 0;
                int left = amount;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    if (i >= 36 && i < 40) continue; // armour stays
                    ItemStack s = inv.getItem(i);
                    if (s.isEmpty() || (item != null && !s.is(item.getItem()))) continue;
                    int take = left < 0 ? s.getCount() : Math.min(left, s.getCount());
                    s.shrink(take);
                    removed += take;
                    if (left > 0) {
                        left -= take;
                        if (left == 0) break;
                    }
                }
                inv.setChanged();
                t.containerMenu.broadcastChanges();
                total += removed;
                if (t != me) t.sendSystemMessage(Messages.get("clearinventory.cleared"));
            }
            reply(ctx, targets.size() == 1 && targets.get(0) == me ? Messages.get("clearinventory.done", total)
                    : Messages.get("clearinventory.doneOthers", total, targets.size()));
            return 1;
        }

        static boolean confirmOn(ServerPlayer p) {
            Boolean b = UserStore.get(p).clearConfirm;
            return b != null ? b : EssConfig.CLEAR_CONFIRM.get();
        }
    }

    static final class ClearConfirmToggle extends EssCommand {
        ClearConfirmToggle() {
            super("clearinventoryconfirmtoggle", Perms.OP, "eclearinventoryconfirmtoggle", "clearinventoryconfirmoff",
                    "eclearinventoryconfirmoff", "clearconfirmoff", "eclearconfirmoff", "clearconfirmon", "eclearconfirmon",
                    "clearconfirm", "eclearconfirm");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                UserData d = UserStore.get(me);
                String label = ctx.getNodes().get(0).getNode().getName();
                boolean on = label.endsWith("off") ? false : label.endsWith("on") ? true : !ClearInventory.confirmOn(me);
                d.clearConfirm = on;
                d.markDirty();
                reply(ctx, Messages.get("clearinventory.confirmState", StateCommands.state(on)));
                return 1;
            }));
        }
    }

    // ---------------------------------------------------------------- give, item, more, itemdb

    static boolean mayGive(CommandSourceStack src, ItemStack stack) {
        return !EssConfig.itemBlacklisted(Items.id(stack)) || Perms.has(src, "essentials.itemspawn.exempt", Perms.ADMIN);
    }

    /** /give <players> <item> [amount]: vanilla's form; without an amount, one item. */
    static final class Give extends EssCommand {
        Give() {
            super("give", Perms.OP, "egive");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("players", EntityArgument.players())
                    .then(Commands.argument("item", ItemArgument.item(buildContext()))
                            .executes(exec(ctx -> give(ctx, EntityArgument.getPlayers(ctx, "players"), ItemArgument.getItem(ctx, "item"), 1)))
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64 * 36 * 4))
                                    .executes(exec(ctx -> give(ctx, EntityArgument.getPlayers(ctx, "players"), ItemArgument.getItem(ctx, "item"),
                                            IntegerArgumentType.getInteger(ctx, "amount")))))));
        }

        private int give(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players, ItemInput item, int amount)
                throws CommandSyntaxException {
            ItemStack template = item.createItemStack(1, false);
            if (!mayGive(ctx.getSource(), template)) return fail(ctx, Messages.get("item.blacklisted", itemName(template)));
            for (ServerPlayer p : players) {
                Items.give(p, template, amount);
                reply(ctx, Messages.get("give.done", amount, itemName(template), p.getDisplayName()));
            }
            return players.size();
        }
    }

    /** /item <item> [amount]: without an amount, a full stack. */
    static final class ItemCommand extends EssCommand {
        ItemCommand() {
            super("item", Perms.OP, "i", "eitem", "ei");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("item", ItemArgument.item(buildContext()))
                    .executes(exec(ctx -> give(ctx, ItemArgument.getItem(ctx, "item"), -1)))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64 * 36 * 4))
                            .executes(exec(ctx -> give(ctx, ItemArgument.getItem(ctx, "item"), IntegerArgumentType.getInteger(ctx, "amount"))))));
        }

        private int give(CommandContext<CommandSourceStack> ctx, ItemInput item, int amount) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            ItemStack template = item.createItemStack(1, false);
            if (!mayGive(ctx.getSource(), template)) return fail(ctx, Messages.get("item.blacklisted", itemName(template)));
            int n = amount < 0 ? template.getMaxStackSize() : amount;
            Items.give(me, template, n);
            reply(ctx, Messages.get("item.given", n, itemName(template)));
            return 1;
        }
    }

    static final class More extends EssCommand {
        More() {
            super("more", Perms.OP, "emore");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ItemStack stack = held(self(ctx));
                if (!mayGive(ctx.getSource(), stack)) return fail(ctx, Messages.get("item.blacklisted", itemName(stack)));
                if (stack.getCount() >= stack.getMaxStackSize()) return fail(ctx, Messages.get("more.full"));
                stack.setCount(stack.getMaxStackSize());
                reply(ctx, Messages.get("more.done", stack.getCount(), itemName(stack)));
                return 1;
            }));
        }
    }

    /** /itemdb [item]: the id and details of the held (or named) item. */
    static final class ItemDb extends EssCommand {
        ItemDb() {
            super("itemdb", Perms.ALL, "eitemdb", "itemno", "eitemno", "durability", "edurability", "dura", "edura");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> show(ctx, held(self(ctx)))))
                    .then(Commands.argument("item", ItemArgument.item(buildContext()))
                            .executes(exec(ctx -> show(ctx, ItemArgument.getItem(ctx, "item").createItemStack(1, false)))));
        }

        private int show(CommandContext<CommandSourceStack> ctx, ItemStack stack) {
            reply(ctx, Messages.get("itemdb.item", itemName(stack), Items.id(stack), stack.getMaxStackSize()));
            if (stack.isDamageableItem()) {
                int max = stack.getMaxDamage();
                reply(ctx, Messages.get("itemdb.durability", max - stack.getDamageValue(), max));
            }
            return 1;
        }
    }

    // ---------------------------------------------------------------- itemname, itemlore

    static Component userText(CommandSourceStack src, String base, String raw) {
        String text = Text.strip(raw, Perms.has(src, base + ".color", Perms.OP), Perms.has(src, base + ".format", Perms.OP),
                Perms.has(src, base + ".magic", Perms.OP));
        // Custom names and lore show in italics unless told otherwise; EssentialsX shows them plain.
        return Component.empty().withStyle(s -> s.withItalic(false)).append(Text.format(text));
    }

    /** /itemname [name]: renames the held item; no name removes the custom name. */
    static final class ItemName extends EssCommand {
        ItemName() {
            super("itemname", Perms.OP, "eitemname", "iname", "einame", "itemrename", "eitemrename", "irename", "eirename");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ItemStack stack = held(self(ctx));
                stack.remove(DataComponents.CUSTOM_NAME);
                reply(ctx, Messages.get("itemname.cleared"));
                return 1;
            })).then(textArg("name").executes(exec(ctx -> {
                ItemStack stack = held(self(ctx));
                stack.set(DataComponents.CUSTOM_NAME, userText(ctx.getSource(), "essentials.itemname", str(ctx, "name")));
                reply(ctx, Messages.get("itemname.set", itemName(stack)));
                return 1;
            })));
        }
    }

    /** /itemlore add <text> | set <line> <text> | clear */
    static final class ItemLoreCommand extends EssCommand {
        ItemLoreCommand() {
            super("itemlore", Perms.OP, "eitemlore", "lore", "elore", "ilore", "eilore");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.literal("add").then(textArg("text").executes(exec(ctx -> {
                        ItemStack stack = held(self(ctx));
                        ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
                        if (lore.lines().size() >= ItemLore.MAX_LINES) return fail(ctx, Messages.get("itemlore.full"));
                        stack.set(DataComponents.LORE, lore.withLineAdded(userText(ctx.getSource(), "essentials.itemlore", str(ctx, "text"))));
                        reply(ctx, Messages.get("itemlore.added"));
                        return 1;
                    }))))
                    .then(Commands.literal("set").then(Commands.argument("line", IntegerArgumentType.integer(1, ItemLore.MAX_LINES))
                            .then(textArg("text").executes(exec(ctx -> {
                                ItemStack stack = held(self(ctx));
                                List<Component> lines = new ArrayList<>(stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
                                int line = IntegerArgumentType.getInteger(ctx, "line");
                                if (line > lines.size()) return fail(ctx, Messages.get("itemlore.noLine", line));
                                lines.set(line - 1, userText(ctx.getSource(), "essentials.itemlore", str(ctx, "text")));
                                stack.set(DataComponents.LORE, new ItemLore(lines));
                                reply(ctx, Messages.get("itemlore.set", line));
                                return 1;
                            })))))
                    .then(Commands.literal("clear").executes(exec(ctx -> {
                        held(self(ctx)).remove(DataComponents.LORE);
                        reply(ctx, Messages.get("itemlore.cleared"));
                        return 1;
                    })));
        }
    }

    // ---------------------------------------------------------------- enchant, repair, hat

    /** /enchant <enchantment> [level]: level 0 removes it. Beyond normal limits needs essentials.enchantments.allowunsafe. */
    static final class Enchant extends EssCommand {
        Enchant() {
            super("enchant", Perms.OP, "eenchant", "enchantment", "eenchantment");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            var ench = Commands.argument("enchantment", ResourceArgument.resource(buildContext(), Registries.ENCHANTMENT));
            root.then(ench.executes(exec(ctx -> enchant(ctx, -1)))
                    .then(Commands.argument("level", IntegerArgumentType.integer(0, 255))
                            .executes(exec(ctx -> enchant(ctx, IntegerArgumentType.getInteger(ctx, "level"))))));
        }

        private int enchant(CommandContext<CommandSourceStack> ctx, int level) throws CommandSyntaxException {
            ItemStack stack = held(self(ctx));
            Holder<Enchantment> e = ResourceArgument.getEnchantment(ctx, "enchantment");
            int lvl = level < 0 ? e.value().getMaxLevel() : level;
            boolean unsafe = Perms.has(ctx.getSource(), "essentials.enchantments.allowunsafe", Perms.ADMIN);
            if (lvl > 0 && !unsafe) {
                if (!e.value().canEnchant(stack) && !stack.is(net.minecraft.world.item.Items.BOOK)
                        && !stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK)) {
                    return fail(ctx, Messages.get("enchant.cantApply", Enchantment.getFullname(e, lvl), itemName(stack)));
                }
                if (lvl > e.value().getMaxLevel()) return fail(ctx, Messages.get("enchant.tooHigh", e.value().getMaxLevel()));
            }
            if (stack.is(net.minecraft.world.item.Items.BOOK) && lvl > 0) {
                ItemStack book = new ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK, stack.getCount());
                self(ctx).setItemInHand(InteractionHand.MAIN_HAND, book);
                stack = book;
            }
            EnchantmentHelper.updateEnchantments(stack, m -> m.set(e, lvl));
            reply(ctx, lvl == 0 ? Messages.get("enchant.removed", e.value().description())
                    : Messages.get("enchant.done", Enchantment.getFullname(e, lvl), itemName(stack)));
            return 1;
        }
    }

    /** /repair [hand|all] */
    static final class Repair extends EssCommand {
        Repair() {
            super("repair", Perms.OP, "erepair", "fix", "efix");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> hand(ctx)))
                    .then(Commands.literal("hand").executes(exec(ctx -> hand(ctx))))
                    .then(Commands.literal("all").requires(src -> has(src, "all", Perms.OP)).executes(exec(ctx -> {
                        ServerPlayer me = self(ctx);
                        int n = 0;
                        for (int i = 0; i < me.getInventory().getContainerSize(); i++) {
                            ItemStack s = me.getInventory().getItem(i);
                            if (s.isDamageableItem() && s.isDamaged()) {
                                s.setDamageValue(0);
                                n++;
                            }
                        }
                        reply(ctx, Messages.get("repair.all", n));
                        return n > 0 ? 1 : 0;
                    })));
        }

        private int hand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            ItemStack stack = held(self(ctx));
            if (!stack.isDamageableItem()) return fail(ctx, Messages.get("repair.cant", itemName(stack)));
            if (!stack.isDamaged()) return fail(ctx, Messages.get("repair.notDamaged", itemName(stack)));
            stack.setDamageValue(0);
            reply(ctx, Messages.get("repair.done", itemName(stack)));
            return 1;
        }
    }

    /** /hat: wear the held item; /hat remove puts the hat back in the inventory. */
    static final class Hat extends EssCommand {
        Hat() {
            super("hat", Perms.OP, "ehat", "head", "ehead");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                ItemStack hand = me.getMainHandItem();
                if (hand.isEmpty()) return fail(ctx, Messages.get("hat.holdSomething"));
                ItemStack head = me.getItemBySlot(EquipmentSlot.HEAD);
                if (EnchantmentHelper.has(head, net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)
                        && !me.isCreative()) {
                    return fail(ctx, Messages.get("hat.curse"));
                }
                ItemStack wear = hand.split(1);
                me.setItemSlot(EquipmentSlot.HEAD, wear);
                if (!head.isEmpty()) Items.give(me, head, head.getCount());
                reply(ctx, Messages.get("hat.placed"));
                return 1;
            })).then(Commands.literal("remove").executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                ItemStack head = me.getItemBySlot(EquipmentSlot.HEAD);
                if (head.isEmpty()) return fail(ctx, Messages.get("hat.none"));
                me.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                Items.give(me, head, head.getCount());
                reply(ctx, Messages.get("hat.removed"));
                return 1;
            })));
        }
    }

    // ---------------------------------------------------------------- condense

    /**
     * /condense [item]: turns items into their storage blocks (9 or 4 to 1) using the server's
     * own crafting recipes, but only where the block crafts back into the same items, so it
     * never makes anything irreversible.
     */
    static final class Condense extends EssCommand {
        private record Rule(int size, ItemStack result) {}

        private static final Map<Item, Optional<Rule>> cache = new ConcurrentHashMap<>();

        Condense() {
            super("condense", Perms.OP, "econdense", "compact", "ecompact", "blocks", "eblocks", "toblocks", "etoblocks");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> condense(ctx, null)))
                    .then(Commands.argument("item", ItemArgument.item(buildContext()))
                            .executes(exec(ctx -> condense(ctx, ItemArgument.getItem(ctx, "item").getItem()))));
        }

        static void clearCache() {
            cache.clear();
        }

        private static Optional<Rule> rule(ServerPlayer p, Item item) {
            return cache.computeIfAbsent(item, i -> {
                for (int size : new int[] {3, 2}) {
                    List<ItemStack> grid = new ArrayList<>();
                    for (int k = 0; k < size * size; k++) grid.add(new ItemStack(i));
                    CraftingInput in = CraftingInput.of(size, size, grid);
                    var recipe = p.server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, in, p.level());
                    if (recipe.isEmpty()) continue;
                    ItemStack out = recipe.get().value().assemble(in, p.registryAccess());
                    if (out.isEmpty() || out.getCount() != 1) continue;
                    CraftingInput back = CraftingInput.of(1, 1, List.of(out.copy()));
                    var undo = p.server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, back, p.level());
                    if (undo.isEmpty()) continue;
                    ItemStack again = undo.get().value().assemble(back, p.registryAccess());
                    if (again.is(i) && again.getCount() == size * size) return Optional.of(new Rule(size * size, out));
                }
                return Optional.empty();
            });
        }

        private int condense(CommandContext<CommandSourceStack> ctx, Item only) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            Inventory inv = me.getInventory();
            Map<Item, Integer> counts = new HashMap<>();
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                // Only plain items: a renamed or enchanted stack isn't turned into a block.
                if (!s.isEmpty() && (only == null || s.is(only)) && s.getComponentsPatch().isEmpty()) counts.merge(s.getItem(), s.getCount(), Integer::sum);
            }
            int made = 0;
            for (var entry : counts.entrySet()) {
                Optional<Rule> r = rule(me, entry.getKey());
                if (r.isEmpty()) continue;
                int blocks = entry.getValue() / r.get().size();
                if (blocks == 0) continue;
                int remove = blocks * r.get().size();
                for (int i = 0; i < 36 && remove > 0; i++) {
                    ItemStack s = inv.getItem(i);
                    if (!s.is(entry.getKey()) || !s.getComponentsPatch().isEmpty()) continue;
                    int take = Math.min(remove, s.getCount());
                    s.shrink(take);
                    remove -= take;
                }
                Items.give(me, r.get().result(), blocks);
                made += blocks;
            }
            if (made == 0) return fail(ctx, Messages.get("condense.nothing"));
            reply(ctx, Messages.get("condense.done", made));
            return 1;
        }
    }

    // ---------------------------------------------------------------- unlimited

    // ---------------------------------------------------------------- skull, book

    /** /skull [player]: a player head (yours without a name). */
    static final class Skull extends EssCommand {
        Skull() {
            super("skull", Perms.OP, "eskull", "playerskull", "eplayerskull", "head2");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> give(ctx, self(ctx).getGameProfile())))
                    .then(Commands.argument("owner", StringArgumentType.word()).suggests(PlayerLookup.ONLINE)
                            .requires(src -> has(src, "others", Perms.OP))
                            .executes(exec(ctx -> {
                                String name = str(ctx, "owner");
                                if (!name.matches("[A-Za-z0-9_]{1,16}")) return fail(ctx, Messages.get("skull.badName", name));
                                ServerPlayer on = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
                                if (on != null) return give(ctx, on.getGameProfile());
                                var known = UserStore.byName(name);
                                return give(ctx, known.map(k -> new GameProfile(k.uuid(), k.name()))
                                        .orElse(new GameProfile(net.minecraft.Util.NIL_UUID, name)));
                            })));
        }

        private int give(CommandContext<CommandSourceStack> ctx, GameProfile profile) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            ItemStack head = new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
            head.set(DataComponents.PROFILE, profile.getId().equals(net.minecraft.Util.NIL_UUID)
                    ? new ResolvableProfile(Optional.of(profile.getName()), Optional.empty(), new com.mojang.authlib.properties.PropertyMap())
                    : new ResolvableProfile(profile));
            Items.give(me, head, 1);
            reply(ctx, Messages.get("skull.given", profile.getName()));
            return 1;
        }
    }

    /** /book unsigns the held written book; /book author <name>, /book title <title> change it. */
    static final class Book extends EssCommand {
        Book() {
            super("book", Perms.OP, "ebook");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                        ServerPlayer me = self(ctx);
                        ItemStack stack = me.getMainHandItem();
                        WrittenBookContent book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
                        if (book == null) return fail(ctx, Messages.get("book.holdWritten"));
                        if (!book.author().equals(me.getGameProfile().getName()) && !has(ctx.getSource(), "others", Perms.OP)) {
                            return fail(ctx, Messages.get("book.notYours"));
                        }
                        List<Filterable<String>> pages = new ArrayList<>();
                        for (Filterable<Component> page : book.pages()) pages.add(Filterable.passThrough(page.raw().getString()));
                        ItemStack writable = new ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK, stack.getCount());
                        writable.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
                        me.setItemInHand(InteractionHand.MAIN_HAND, writable);
                        reply(ctx, Messages.get("book.unsigned"));
                        return 1;
                    }))
                    .then(Commands.literal("author").requires(src -> has(src, "author", Perms.OP))
                            .then(Commands.argument("name", StringArgumentType.word()).executes(exec(ctx -> edit(ctx, str(ctx, "name"), null)))))
                    .then(Commands.literal("title").requires(src -> has(src, "title", Perms.OP))
                            .then(textArg("title").executes(exec(ctx -> edit(ctx, null, str(ctx, "title"))))));
        }

        private int edit(CommandContext<CommandSourceStack> ctx, String author, String title) throws CommandSyntaxException {
            ItemStack stack = self(ctx).getMainHandItem();
            WrittenBookContent book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (book == null) return fail(ctx, Messages.get("book.holdWritten"));
            if (title != null && title.length() > WrittenBookContent.TITLE_MAX_LENGTH) {
                return fail(ctx, Messages.get("book.titleTooLong", WrittenBookContent.TITLE_MAX_LENGTH));
            }
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                    title != null ? Filterable.passThrough(title) : book.title(),
                    author != null ? author : book.author(), book.generation(), book.pages(), book.resolved()));
            reply(ctx, Messages.get(author != null ? "book.authorSet" : "book.titleSet", author != null ? author : title));
            return 1;
        }
    }

    // ---------------------------------------------------------------- recipe

    /** /recipe <item> [number]: shows a recipe in a read-only GUI (ingredients left, result right). */
    static final class RecipeCommand extends EssCommand {
        RecipeCommand() {
            super("recipe", Perms.ALL, "erecipe", "formula", "eformula", "method", "emethod", "recipes", "erecipes");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            var item = Commands.argument("item", ItemArgument.item(buildContext()));
            root.then(item.executes(exec(ctx -> show(ctx, 1)))
                    .then(Commands.argument("number", IntegerArgumentType.integer(1))
                            .executes(exec(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "number"))))));
        }

        private int show(CommandContext<CommandSourceStack> ctx, int number) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            Item item = ItemArgument.getItem(ctx, "item").getItem();
            List<RecipeHolder<?>> found = new ArrayList<>();
            for (RecipeHolder<?> r : me.server.getRecipeManager().getRecipes()) {
                if (r.value().isSpecial()) continue;
                ItemStack out = r.value().getResultItem(me.registryAccess());
                if (out.is(item)) found.add(r);
            }
            if (found.isEmpty()) return fail(ctx, Messages.get("recipe.none", new ItemStack(item).getHoverName()));
            if (number > found.size()) return fail(ctx, Messages.get("recipe.noNumber", found.size()));
            Recipe<?> recipe = found.get(number - 1).value();
            SimpleContainer grid = new SimpleContainer(27);
            NonNullList<Ingredient> ings = recipe.getIngredients();
            int width = recipe instanceof ShapedRecipe s ? s.getWidth() : (recipe instanceof CraftingRecipe ? 3 : Math.max(1, ings.size()));
            for (int i = 0; i < ings.size() && i < 9; i++) {
                ItemStack[] options = ings.get(i).getItems();
                if (options.length == 0) continue;
                int row = width <= 3 ? i / width : i / 3, col = width <= 3 ? i % width : i % 3;
                grid.setItem(row * 9 + col + 1, options[0].copyWithCount(1));
            }
            grid.setItem(9 + 4, new ItemStack(net.minecraft.world.item.Items.ARROW).copy());
            grid.getItem(13).set(DataComponents.CUSTOM_NAME, Messages.get("recipe.arrow", recipe.getType().toString()));
            grid.setItem(9 + 6, recipe.getResultItem(me.registryAccess()).copy());
            Menus.openView(me, Messages.get("recipe.title", new ItemStack(item).getHoverName(), number, found.size()), grid, null);
            if (found.size() > 1) reply(ctx, Messages.get("recipe.more", found.size()));
            return 1;
        }
    }

    // ---------------------------------------------------------------- power tools

    // ---------------------------------------------------------------- editsign

    /** /editsign set <line> <text> | clear [line] | copy | paste: the sign you're looking at (the side facing you). */
    static final class EditSign extends EssCommand {
        private static final Map<UUID, Component[]> clipboard = new ConcurrentHashMap<>();

        EditSign() {
            super("editsign", Perms.OP, "eeditsign", "sign", "esign");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            var line = Commands.argument("line", IntegerArgumentType.integer(1, 4));
            root.then(Commands.literal("set").then(line.then(textArg("text").executes(exec(ctx -> {
                        Component text = Text.format(Text.strip(str(ctx, "text"), has(ctx.getSource(), "color", Perms.OP),
                                has(ctx.getSource(), "format", Perms.OP), has(ctx.getSource(), "magic", Perms.OP)));
                        return change(ctx, t -> t.setMessage(IntegerArgumentType.getInteger(ctx, "line") - 1, text));
                    })))))
                    .then(Commands.literal("clear").executes(exec(ctx -> change(ctx, t -> {
                                SignText out = t;
                                for (int i = 0; i < 4; i++) out = out.setMessage(i, Component.empty());
                                return out;
                            })))
                            .then(Commands.argument("line", IntegerArgumentType.integer(1, 4)).executes(exec(ctx ->
                                    change(ctx, t -> t.setMessage(IntegerArgumentType.getInteger(ctx, "line") - 1, Component.empty()))))))
                    .then(Commands.literal("copy").executes(exec(ctx -> {
                        ServerPlayer me = self(ctx);
                        SignBlockEntity sign = target(me);
                        SignText t = sign.getText(sign.isFacingFrontText(me));
                        Component[] lines = new Component[4];
                        for (int i = 0; i < 4; i++) lines[i] = t.getMessage(i, false);
                        clipboard.put(me.getUUID(), lines);
                        reply(ctx, Messages.get("editsign.copied"));
                        return 1;
                    })))
                    .then(Commands.literal("paste").executes(exec(ctx -> {
                        Component[] lines = clipboard.get(self(ctx).getUUID());
                        if (lines == null) return fail(ctx, Messages.get("editsign.nothingCopied"));
                        return change(ctx, t -> {
                            SignText out = t;
                            for (int i = 0; i < 4; i++) out = out.setMessage(i, lines[i]);
                            return out;
                        });
                    })));
        }

        private static SignBlockEntity target(ServerPlayer me) throws CommandSyntaxException {
            HitResult hit = me.pick(me.blockInteractionRange(), 1f, false);
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                    && me.level().getBlockEntity(bhr.getBlockPos()) instanceof SignBlockEntity sign) {
                return sign;
            }
            throw EssCommandErrors.error("editsign.lookAtSign");
        }

        private int change(CommandContext<CommandSourceStack> ctx, java.util.function.UnaryOperator<SignText> edit) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            SignBlockEntity sign = target(me);
            if (sign.isWaxed() && !has(ctx.getSource(), "waxed", Perms.OP)) return fail(ctx, Messages.get("editsign.waxed"));
            sign.updateText(edit, sign.isFacingFrontText(me));
            reply(ctx, Messages.get("editsign.done"));
            return 1;
        }
    }

    private ItemCommands() {}
}
