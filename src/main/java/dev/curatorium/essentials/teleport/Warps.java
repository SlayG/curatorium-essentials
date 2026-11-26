package dev.curatorium.essentials.teleport;

import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.JsonFile;
import dev.curatorium.essentials.data.Location;
import dev.curatorium.essentials.menu.Menus;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Durations;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Warps, in {@code <world>/curatorium_essentials/warps.json}. With warps.perWarpPermission on,
 * each warp also needs essentials.warps.<name>.
 */
public final class Warps {
    private static final String FILE = "warps.json";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    static final class Warp {
        Location location;
        String creator = "";
        long created;
    }

    private static Map<String, Warp> warps = new TreeMap<>();

    static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, b) -> SharedSuggestionProvider.suggest(
            warps.keySet().stream().filter(n -> mayUse(ctx.getSource(), n)).toList(), b);

    public static void register() {
        CommandRegistry.add(new SetWarp());
        CommandRegistry.add(new WarpCommand());
        CommandRegistry.add(new DelWarp());
        CommandRegistry.add(new WarpInfo());
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(ServerStartedEvent.class, e -> {
            Map<String, Warp> loaded = JsonFile.read(FILE, new TypeToken<TreeMap<String, Warp>>() {}.getType(), () -> new TreeMap<String, Warp>());
            loaded.values().removeIf(w -> w == null || w.location == null);
            warps = new TreeMap<>(loaded);
        });
    }

    private static void save() {
        JsonFile.write(FILE, warps);
    }

    /** A warp's location, or null. */
    public static Location location(String name) {
        Warp w = warps.get(name);
        return w == null ? null : w.location;
    }

    static boolean mayUse(CommandSourceStack src, String name) {
        return !EssConfig.PER_WARP_PERMISSION.get() || Perms.has(src, "essentials.warps." + name, Perms.OP);
    }

    static int warp(CommandContext<CommandSourceStack> ctx, ServerPlayer p, String name) {
        Warp w = warps.get(name);
        if (w == null) {
            ctx.getSource().sendFailure(Messages.get("warp.unknown", name));
            return 0;
        }
        if (!mayUse(ctx.getSource(), name)) {
            ctx.getSource().sendFailure(Messages.get("warp.noPermission", name));
            return 0;
        }
        return Teleports.go(p, w.location, true, Messages.get("warp.arrived", name)) ? 1 : 0;
    }

    static int gui(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
        List<String> names = warps.keySet().stream().filter(n -> mayUse(ctx.getSource(), n)).toList();
        if (names.isEmpty()) {
            ctx.getSource().sendFailure(Messages.get("warp.none"));
            return 0;
        }
        int rows = Math.min(6, (names.size() + 8) / 9);
        SimpleContainer icons = new SimpleContainer(rows * 9);
        for (int i = 0; i < names.size() && i < icons.getContainerSize(); i++) {
            Location l = warps.get(names.get(i)).location;
            ItemStack icon = new ItemStack(Items.ENDER_EYE);
            icon.set(DataComponents.CUSTOM_NAME, Messages.get("warp.icon.name", names.get(i)).withStyle(s -> s.withItalic(false)));
            icon.set(DataComponents.LORE, new ItemLore(List.of(Messages.get("home.icon.where", l.dimension(),
                    (int) Math.floor(l.x()), (int) Math.floor(l.y()), (int) Math.floor(l.z())))));
            icons.setItem(i, icon);
        }
        Menus.openView(p, Messages.get("warp.title"), icons, slot -> {
            if (slot >= names.size()) return;
            p.closeContainer();
            Warp w = warps.get(names.get(slot));
            if (w != null) Teleports.go(p, w.location, true, Messages.get("warp.arrived", names.get(slot)));
        });
        return 1;
    }

    // ---------------------------------------------------------------- commands

    static final class SetWarp extends EssCommand {
        SetWarp() {
            super("setwarp", Perms.OP, "esetwarp", "createwarp", "ecreatewarp");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                String name = str(ctx, "name").toLowerCase(Locale.ROOT);
                if (!NAME.matcher(name).matches()) return fail(ctx, Messages.get("warp.badName"));
                Warp w = warps.get(name);
                boolean replaced = w != null;
                if (w == null) {
                    w = new Warp();
                    w.creator = me.getGameProfile().getName();
                    w.created = System.currentTimeMillis();
                } else if (!has(ctx.getSource(), "overwrite", Perms.OP)) {
                    return fail(ctx, Messages.get("warp.exists", name));
                }
                w.location = Location.of(me);
                warps.put(name, w);
                save();
                reply(ctx, Messages.get(replaced ? "warp.moved" : "warp.set", name));
                return 1;
            })));
        }
    }

    /** /warp opens the warp GUI; /warp <name> [player] goes (or sends someone). */
    static final class WarpCommand extends EssCommand {
        WarpCommand() {
            super("warp", Perms.OP, "ewarp", "warps", "ewarps");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> gui(ctx, self(ctx))))
                    .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                            .executes(exec(ctx -> warp(ctx, self(ctx), str(ctx, "name").toLowerCase(Locale.ROOT))))
                            .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                                    .executes(exec(ctx -> {
                                        ServerPlayer p = online(ctx, "player");
                                        int r = warp(ctx, p, str(ctx, "name").toLowerCase(Locale.ROOT));
                                        if (r > 0) tellOther(ctx, p, Messages.get("warp.sent", p.getDisplayName(), str(ctx, "name")));
                                        return r;
                                    }))));
        }
    }

    static final class DelWarp extends EssCommand {
        DelWarp() {
            super("delwarp", Perms.OP, "edelwarp", "remwarp", "eremwarp", "rmwarp", "ermwarp");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(exec(ctx -> {
                String name = str(ctx, "name").toLowerCase(Locale.ROOT);
                if (warps.remove(name) == null) return fail(ctx, Messages.get("warp.unknown", name));
                save();
                reply(ctx, Messages.get("warp.deleted", name));
                return 1;
            })));
        }
    }

    static final class WarpInfo extends EssCommand {
        WarpInfo() {
            super("warpinfo", Perms.OP, "ewarpinfo");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(exec(ctx -> {
                String name = str(ctx, "name").toLowerCase(Locale.ROOT);
                Warp w = warps.get(name);
                if (w == null) return fail(ctx, Messages.get("warp.unknown", name));
                Location l = w.location;
                reply(ctx, Messages.get("warp.info", name, l.dimension(), String.format(Locale.ROOT, "%.1f, %.1f, %.1f", l.x(), l.y(), l.z()),
                        w.creator, w.created > 0 ? Durations.format(System.currentTimeMillis() - w.created) : "?"));
                return 1;
            })));
        }
    }

    private Warps() {}
}
