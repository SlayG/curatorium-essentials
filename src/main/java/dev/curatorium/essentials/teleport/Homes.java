package dev.curatorium.essentials.teleport;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.Location;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.menu.Menus;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Homes: /sethome, /home, /delhome, /renamehome, /homes (GUI). Another player's homes are
 * reached as {@code player:home} (needs essentials.<command>.others). The home "bed" is the
 * player's bed or respawn anchor. Op-only by default, like every teleport.
 */
public final class Homes {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    static final String DEFAULT = "home";

    public static void register() {
        CommandRegistry.add(new SetHome());
        CommandRegistry.add(new Home());
        CommandRegistry.add(new HomesGui());
        CommandRegistry.add(new DelHome());
        CommandRegistry.add(new RenameHome());
    }

    /** Suggests your homes (and "bed"). */
    static final SuggestionProvider<CommandSourceStack> MINE = (ctx, b) -> {
        ServerPlayer p = ctx.getSource().getPlayer();
        List<String> names = new ArrayList<>();
        if (p != null) names.addAll(UserStore.get(p).homes.keySet());
        names.add("bed");
        return SharedSuggestionProvider.suggest(names, b);
    };

    /** How many homes a player may have: the default, or more for a LuckPerms group in homes.groupMax. */
    static int max(ServerPlayer p) {
        if (Perms.has(p, "essentials.sethome.multiple.unlimited", Perms.ADMIN)) return Integer.MAX_VALUE;
        long max = EssConfig.HOMES_DEFAULT.get();
        for (Map.Entry<String, Long> g : EssConfig.homeGroups().entrySet()) {
            if (g.getValue() > max && Perms.has(p, "group." + g.getKey(), Perms.NOBODY)) max = g.getValue();
        }
        return (int) Math.min(Integer.MAX_VALUE, max);
    }

    /** "name" or "player:name", resolved to whose homes and which home. */
    record Ref(PlayerLookup.Target owner, String home, boolean other) {}

    static Ref ref(CommandContext<CommandSourceStack> ctx, String input, String othersNode) throws CommandSyntaxException {
        ServerPlayer me = ctx.getSource().getPlayer();
        String text = input == null ? DEFAULT : input.toLowerCase(Locale.ROOT);
        int colon = text.indexOf(':');
        if (colon < 0) {
            if (me == null) throw ItemErrors.of("home.consoleNeedsPlayer");
            return new Ref(new PlayerLookup.Target(me.getUUID(), me.getGameProfile().getName(), me), text, false);
        }
        if (!Perms.has(ctx.getSource(), othersNode, Perms.OP)) throw ItemErrors.of("command.noPermission");
        String who = text.substring(0, colon);
        PlayerLookup.Target t = PlayerLookup.any(ctx.getSource().getServer(), who)
                .orElseThrow(() -> ItemErrors.of("player.notFound", who));
        String home = text.substring(colon + 1);
        return new Ref(t, home.isEmpty() ? DEFAULT : home, me == null || !t.uuid().equals(me.getUUID()));
    }

    /** Red errors from static helpers. */
    static final class ItemErrors {
        static CommandSyntaxException of(String key, Object... args) {
            return new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Messages.get(key, args)).create();
        }
    }

    /** The bed or respawn anchor the player would respawn at, or null. */
    static Location bed(ServerPlayer p) {
        BlockPos pos = p.getRespawnPosition();
        ServerLevel level = p.server.getLevel(p.getRespawnDimension());
        if (pos == null || level == null) return null;
        BlockState state = level.getBlockState(pos);
        var spot = state.getRespawnPosition(net.minecraft.world.entity.EntityType.PLAYER, level, pos, p.getRespawnAngle());
        return spot.map(v -> new Location(level.dimension().location().toString(), v.position().x, v.position().y, v.position().z,
                v.yaw(), 0)).orElse(null);
    }

    static int go(CommandContext<CommandSourceStack> ctx, ServerPlayer mover, Ref ref) {
        Location dest;
        if (ref.home().equals("bed")) {
            if (!ref.owner().isOnline()) return fail(ctx, Messages.get("home.bedOffline"));
            dest = bed(ref.owner().online());
            if (dest == null) return fail(ctx, Messages.get("home.noBed"));
        } else {
            dest = UserStore.get(ref.owner().uuid()).homes.get(ref.home());
            if (dest == null) return fail(ctx, Messages.get("home.unknown", ref.home()));
        }
        return Teleports.go(mover, dest, true, Messages.get("home.arrived", ref.home())) ? 1 : 0;
    }

    static int fail(CommandContext<CommandSourceStack> ctx, Component message) {
        ctx.getSource().sendFailure(message);
        return 0;
    }

    /** Opens a GUI of a player's homes; clicking one goes there. */
    static int gui(CommandContext<CommandSourceStack> ctx, ServerPlayer viewer, PlayerLookup.Target owner) {
        Map<String, Location> homes = UserStore.get(owner.uuid()).homes;
        if (homes.isEmpty()) return fail(ctx, Messages.get("home.none"));
        List<String> names = new ArrayList<>(homes.keySet());
        int rows = Math.min(6, (names.size() + 8) / 9);
        SimpleContainer icons = new SimpleContainer(rows * 9);
        for (int i = 0; i < names.size() && i < icons.getContainerSize(); i++) {
            Location l = homes.get(names.get(i));
            ItemStack icon = new ItemStack(Items.RED_BED);
            icon.set(DataComponents.CUSTOM_NAME, Messages.get("home.icon.name", names.get(i)).withStyle(s -> s.withItalic(false)));
            icon.set(DataComponents.LORE, new ItemLore(List.of(Messages.get("home.icon.where", l.dimension(),
                    (int) Math.floor(l.x()), (int) Math.floor(l.y()), (int) Math.floor(l.z())))));
            icons.setItem(i, icon);
        }
        boolean other = !owner.uuid().equals(viewer.getUUID());
        Menus.openView(viewer, other ? Messages.get("home.titleOther", owner.name()) : Messages.get("home.title"), icons, slot -> {
            if (slot >= names.size()) return;
            viewer.closeContainer();
            Location dest = UserStore.get(owner.uuid()).homes.get(names.get(slot));
            if (dest != null) Teleports.go(viewer, dest, true, Messages.get("home.arrived", names.get(slot)));
        });
        return 1;
    }

    // ---------------------------------------------------------------- commands

    static final class SetHome extends EssCommand {
        SetHome() {
            super("sethome", Perms.OP, "esethome", "createhome", "ecreatehome");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> set(ctx, null)))
                    .then(Commands.argument("name", StringArgumentType.greedyString()).suggests(MINE)
                            .executes(exec(ctx -> set(ctx, str(ctx, "name").trim()))));
        }

        private int set(CommandContext<CommandSourceStack> ctx, String input) throws CommandSyntaxException {
            ServerPlayer me = self(ctx);
            Ref ref = ref(ctx, input, "essentials.sethome.others");
            if (!NAME.matcher(ref.home()).matches() || ref.home().equals("bed")) return fail(ctx, Messages.get("home.badName"));
            UserData d = UserStore.get(ref.owner().uuid());
            if (!d.homes.containsKey(ref.home()) && !ref.other()) {
                int max = max(me);
                if (d.homes.size() >= max) return fail(ctx, Messages.get("home.limit", max));
            }
            d.homes.put(ref.home(), Location.of(me));
            d.markDirty();
            reply(ctx, ref.other() ? Messages.get("home.setOther", ref.home(), ref.owner().name()) : Messages.get("home.set", ref.home()));
            return 1;
        }
    }

    /** /home [name|player:name]: with several homes and no name, opens the GUI. */
    static final class Home extends EssCommand {
        Home() {
            super("home", Perms.OP, "ehome");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                Map<String, Location> homes = UserStore.get(me).homes;
                PlayerLookup.Target mine = new PlayerLookup.Target(me.getUUID(), me.getGameProfile().getName(), me);
                if (homes.size() == 1) return go(ctx, me, new Ref(mine, homes.keySet().iterator().next(), false));
                return gui(ctx, me, mine);
            })).then(Commands.argument("name", StringArgumentType.greedyString()).suggests(MINE)
                    .executes(exec(ctx -> {
                        ServerPlayer me = self(ctx);
                        String input = str(ctx, "name").trim();
                        // "player:" alone lists that player's homes.
                        if (input.endsWith(":")) {
                            Ref ref = ref(ctx, input, "essentials.home.others");
                            return gui(ctx, me, ref.owner());
                        }
                        return go(ctx, me, ref(ctx, input, "essentials.home.others"));
                    })));
        }
    }

    /** /homes [player] always opens the GUI. Uses /home's node. */
    static final class HomesGui extends EssCommand {
        HomesGui() {
            super("homes", "essentials.home", Perms.OP, "ehomes", "listhomes", "elisthomes", "homelist", "ehomelist");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                return gui(ctx, me, new PlayerLookup.Target(me.getUUID(), me.getGameProfile().getName(), me));
            })).then(playerArg("player").requires(src -> Perms.has(src, "essentials.home.others", Perms.OP))
                    .executes(exec(ctx -> gui(ctx, self(ctx), known(ctx, "player")))));
        }
    }

    static final class DelHome extends EssCommand {
        DelHome() {
            super("delhome", Perms.OP, "edelhome", "remhome", "eremhome", "rmhome", "ermhome");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.argument("name", StringArgumentType.greedyString()).suggests(MINE).executes(exec(ctx -> {
                Ref ref = ref(ctx, str(ctx, "name").trim(), "essentials.delhome.others");
                UserData d = UserStore.get(ref.owner().uuid());
                if (d.homes.remove(ref.home()) == null) return fail(ctx, Messages.get("home.unknown", ref.home()));
                d.markDirty();
                reply(ctx, Messages.get("home.deleted", ref.home()));
                return 1;
            })));
        }
    }

    static final class RenameHome extends EssCommand {
        RenameHome() {
            super("renamehome", Perms.OP, "erenamehome");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            // One greedy argument split by hand: "player:home" doesn't fit a single brigadier word.
            root.then(Commands.argument("home and new name", StringArgumentType.greedyString()).suggests(MINE).executes(exec(ctx -> {
                        String[] parts = str(ctx, "home and new name").trim().split("\\s+");
                        if (parts.length != 2) return fail(ctx, Messages.get("home.renameUsage"));
                        Ref ref = ref(ctx, parts[0], "essentials.renamehome.others");
                        String to = parts[1].toLowerCase(Locale.ROOT);
                        if (!NAME.matcher(to).matches() || to.equals("bed")) return fail(ctx, Messages.get("home.badName"));
                        UserData d = UserStore.get(ref.owner().uuid());
                        if (d.homes.containsKey(to)) return fail(ctx, Messages.get("home.exists", to));
                        Location l = d.homes.remove(ref.home());
                        if (l == null) return fail(ctx, Messages.get("home.unknown", ref.home()));
                        d.homes.put(to, l);
                        d.markDirty();
                        reply(ctx, Messages.get("home.renamed", ref.home(), to));
                        return 1;
                    })));
        }
    }

    private Homes() {}
}
