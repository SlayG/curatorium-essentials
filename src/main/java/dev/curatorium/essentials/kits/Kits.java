package dev.curatorium.essentials.kits;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.serialization.JsonOps;
import dev.curatorium.essentials.CuratoriumEssentials;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.JsonFile;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.menu.Menus;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Durations;
import dev.curatorium.essentials.util.Items;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Kits, kept in {@code <world>/curatorium_essentials/kits.json}. Each kit has items (saved with
 * all their data: enchantments, names, contents) and a delay: seconds between uses, 0 for none,
 * -1 for once ever. Using a kit needs {@code essentials.kits.<name>} (default op).
 */
public final class Kits {
    private static final String FILE = "kits.json";

    /** One kit as saved. */
    static final class Kit {
        long delay;
        List<JsonElement> items = new ArrayList<>();
    }

    private static Map<String, Kit> kits = new TreeMap<>();

    static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, b) -> SharedSuggestionProvider.suggest(kits.keySet(), b);

    public static void register() {
        CommandRegistry.add(new KitCommand());
        CommandRegistry.add(new CreateKit());
        CommandRegistry.add(new DelKit());
        CommandRegistry.add(new ShowKit());
        CommandRegistry.add(new KitReset());
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(ServerStartedEvent.class, e -> {
            Map<String, Kit> loaded = JsonFile.read(FILE, new TypeToken<TreeMap<String, Kit>>() {}.getType(), () -> new TreeMap<String, Kit>());
            kits = new TreeMap<>(loaded);
        });
        // LOWEST: after the player store has recorded this login, so a first join can be recognised.
        bus.addListener(EventPriority.LOWEST, PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            String name = EssConfig.FIRST_JOIN_KIT.get().trim().toLowerCase(Locale.ROOT);
            UserData d = UserStore.get(p);
            if (name.isEmpty() || d.firstJoin != d.lastLogin) return;
            Kit kit = kits.get(name);
            if (kit == null) {
                CuratoriumEssentials.LOGGER.warn("kits.firstJoinKit is \"{}\", but there is no such kit", name);
                return;
            }
            give(p, kit);
            markUsed(d, name);
        });
    }

    private static void save() {
        JsonFile.write(FILE, kits);
    }

    private static RegistryOps<JsonElement> ops(MinecraftServer server) {
        return RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
    }

    static List<ItemStack> items(MinecraftServer server, Kit kit) {
        List<ItemStack> out = new ArrayList<>();
        for (JsonElement el : kit.items) {
            ItemStack.CODEC.parse(ops(server), el)
                    .resultOrPartial(err -> CuratoriumEssentials.LOGGER.warn("Skipping a kit item that can't be read: {}", err))
                    .ifPresent(out::add);
        }
        return out;
    }

    static void give(ServerPlayer p, Kit kit) {
        for (ItemStack stack : items(p.server, kit)) Items.give(p, stack, stack.getCount());
    }

    static void markUsed(UserData d, String name) {
        d.kitsUsed.put(name, System.currentTimeMillis());
        d.markDirty();
    }

    /** Millis until the player may take the kit again: 0 = now, -1 = never again. */
    static long waitFor(UserData d, String name, Kit kit) {
        Long last = d.kitsUsed.get(name);
        if (last == null || kit.delay == 0) return 0;
        if (kit.delay < 0) return -1;
        return Math.max(0, last + kit.delay * 1000 - System.currentTimeMillis());
    }

    static boolean mayUse(CommandSourceStack src, String name) {
        return Perms.has(src, "essentials.kits." + name, Perms.OP);
    }

    static String delayText(long delay) {
        if (delay < 0) return Messages.raw("kit.delayOnce");
        if (delay == 0) return Messages.raw("kit.delayNone");
        return Durations.format(delay * 1000);
    }

    /**
     * Gives a kit, checking its delay for {@code target} unless the source may skip delays.
     * Returns 1 if given.
     */
    static int claim(CommandSourceStack src, ServerPlayer target, String name) {
        Kit kit = kits.get(name);
        if (kit == null) {
            src.sendFailure(Messages.get("kit.unknown", name));
            return 0;
        }
        if (!mayUse(src, name)) {
            src.sendFailure(Messages.get("kit.noPermission", name));
            return 0;
        }
        UserData d = UserStore.get(target);
        long wait = waitFor(d, name, kit);
        if (wait != 0 && !Perms.has(src, "essentials.kit.exemptdelay", Perms.ADMIN)) {
            src.sendFailure(wait < 0 ? Messages.get("kit.onceOnly", name) : Messages.get("kit.wait", name, Durations.format(wait)));
            return 0;
        }
        give(target, kit);
        markUsed(d, name);
        target.sendSystemMessage(Messages.get("kit.received", name));
        if (src.getPlayer() != target) src.sendSuccess(() -> Messages.get("kit.givenOther", name, target.getDisplayName()), false);
        return 1;
    }

    // ---------------------------------------------------------------- commands

    /** /kit opens the kit picker (lists kits for the console); /kit <name> [player] takes one. */
    static final class KitCommand extends EssCommand {
        KitCommand() {
            super("kit", Perms.ALL, "ekit", "kits", "ekits");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(this::picker))
                    .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                            .executes(exec(ctx -> claim(ctx.getSource(), self(ctx), str(ctx, "name").toLowerCase(Locale.ROOT))))
                            .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                                    .executes(exec(ctx -> claim(ctx.getSource(), online(ctx, "player"), str(ctx, "name").toLowerCase(Locale.ROOT))))));
        }

        private int picker(CommandContext<CommandSourceStack> ctx) {
            CommandSourceStack src = ctx.getSource();
            List<String> usable = new ArrayList<>();
            for (String name : kits.keySet()) if (mayUse(src, name)) usable.add(name);
            ServerPlayer me = src.getPlayer();
            if (usable.isEmpty()) return fail(ctx, Messages.get("kit.noneAvailable"));
            if (me == null) {
                reply(ctx, Messages.get("kit.list", String.join(", ", usable)));
                return 1;
            }
            UserData d = UserStore.get(me);
            int rows = Math.min(6, (usable.size() + 8) / 9);
            SimpleContainer icons = new SimpleContainer(rows * 9);
            for (int i = 0; i < usable.size() && i < icons.getContainerSize(); i++) {
                String name = usable.get(i);
                Kit kit = kits.get(name);
                List<ItemStack> items = items(me.server, kit);
                ItemStack icon = items.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.CHEST) : items.get(0).copyWithCount(1);
                icon.set(DataComponents.CUSTOM_NAME, Messages.get("kit.icon.name", name).withStyle(s -> s.withItalic(false)));
                long wait = waitFor(d, name, kit);
                List<Component> lore = new ArrayList<>();
                lore.add(wait == 0 ? Messages.get("kit.icon.ready") : wait < 0 ? Messages.get("kit.icon.claimed")
                        : Messages.get("kit.icon.wait", Durations.format(wait)));
                lore.add(Messages.get("kit.icon.items", items.size()));
                icon.set(DataComponents.LORE, new ItemLore(lore));
                icons.setItem(i, icon);
            }
            Menus.openView(me, Messages.get("kit.title"), icons, slot -> {
                if (slot >= usable.size()) return;
                me.closeContainer();
                claim(me.createCommandSourceStack(), me, usable.get(slot));
            });
            return 1;
        }
    }

    /** /createkit <name> <delay>: the kit is everything you're carrying. Delay: seconds, a length like 1d, 0, or "once". */
    static final class CreateKit extends EssCommand {
        CreateKit() {
            super("createkit", Perms.ADMIN, "ecreatekit", "kitcreate", "ekitcreate", "setkit", "esetkit");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                    .then(Commands.argument("delay", StringArgumentType.word()).executes(exec(ctx -> {
                        ServerPlayer me = self(ctx);
                        String name = str(ctx, "name").toLowerCase(Locale.ROOT);
                        String delayText = str(ctx, "delay").toLowerCase(Locale.ROOT);
                        long delay;
                        if (delayText.equals("once") || delayText.equals("-1")) {
                            delay = -1;
                        } else if (delayText.matches("\\d+")) {
                            delay = Long.parseLong(delayText);
                        } else {
                            OptionalLong d = Durations.parse(delayText);
                            if (d.isEmpty()) return fail(ctx, Messages.get("duration.invalid", delayText));
                            delay = d.getAsLong() / 1000;
                        }
                        Kit kit = new Kit();
                        kit.delay = delay;
                        var inv = me.getInventory();
                        for (int i = 0; i < inv.getContainerSize(); i++) {
                            ItemStack s = inv.getItem(i);
                            if (s.isEmpty()) continue;
                            ItemStack.CODEC.encodeStart(ops(me.server), s).result().ifPresent(kit.items::add);
                        }
                        if (kit.items.isEmpty()) return fail(ctx, Messages.get("kit.emptyInventory"));
                        boolean replaced = kits.put(name, kit) != null;
                        save();
                        reply(ctx, Messages.get(replaced ? "kit.replaced" : "kit.created", name, kit.items.size(), delayText(delay)));
                        return 1;
                    }))));
        }
    }

    static final class DelKit extends EssCommand {
        DelKit() {
            super("delkit", Perms.ADMIN, "edelkit", "deletekit", "edeletekit", "kitdelete", "ekitdelete", "removekit", "eremovekit");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(exec(ctx -> {
                String name = str(ctx, "name").toLowerCase(Locale.ROOT);
                if (kits.remove(name) == null) return fail(ctx, Messages.get("kit.unknown", name));
                save();
                reply(ctx, Messages.get("kit.deleted", name));
                return 1;
            })));
        }
    }

    static final class ShowKit extends EssCommand {
        ShowKit() {
            super("showkit", Perms.OP, "eshowkit", "kitpreview", "ekitpreview", "previewkit", "epreviewkit");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(exec(ctx -> {
                String name = str(ctx, "name").toLowerCase(Locale.ROOT);
                Kit kit = kits.get(name);
                if (kit == null) return fail(ctx, Messages.get("kit.unknown", name));
                reply(ctx, Messages.get("kit.show", name, delayText(kit.delay)));
                for (ItemStack s : items(ctx.getSource().getServer(), kit)) {
                    reply(ctx, Messages.get("kit.showItem", s.getCount(), s.getHoverName()));
                }
                return 1;
            })));
        }
    }

    /** /kitreset <name> [player]: lets them take the kit again now. */
    static final class KitReset extends EssCommand {
        KitReset() {
            super("kitreset", Perms.ADMIN, "ekitreset", "resetkit", "eresetkit");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                    .executes(exec(ctx -> reset(ctx, new PlayerLookup.Target(self(ctx).getUUID(), self(ctx).getGameProfile().getName(), self(ctx)))))
                    .then(playerArg("player").requires(src -> has(src, "others", Perms.ADMIN))
                            .executes(exec(ctx -> reset(ctx, known(ctx, "player"))))));
        }

        private int reset(CommandContext<CommandSourceStack> ctx, PlayerLookup.Target target) {
            String name = str(ctx, "name").toLowerCase(Locale.ROOT);
            if (!kits.containsKey(name)) return fail(ctx, Messages.get("kit.unknown", name));
            UserData d = UserStore.get(target.uuid());
            d.kitsUsed.remove(name);
            d.markDirty();
            reply(ctx, Messages.get("kit.reset", name, target.name()));
            return 1;
        }
    }

    private Kits() {}
}
