package dev.curatorium.essentials.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.Location;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.economy.Money;
import dev.curatorium.essentials.economy.Wallet;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.player.Afk;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.TextFiles;
import dev.curatorium.essentials.util.Durations;
import dev.curatorium.essentials.util.PlayerLookup;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Information commands: who's online, text files, help, positions and player lookups. */
public final class InfoCommands {
    public static void register() {
        CommandRegistry.add(new ListCommand());
        CommandRegistry.add(new TextFileCommand("motd", "motd.txt", false, "emotd"));
        CommandRegistry.add(new TextFileCommand("rules", "rules.txt", false, "erules"));
        CommandRegistry.add(new TextFileCommand("info", "info.txt", true,
                "about", "eabout", "ifo", "eifo", "einfo", "inform", "einform", "news", "enews"));
        CommandRegistry.add(new TextFileCommand("customtext", "custom.txt", true, "ecustomtext"));
        CommandRegistry.add(new Help());
        CommandRegistry.add(new Ping());
        CommandRegistry.add(new Playtime());
        CommandRegistry.add(new Seen());
        CommandRegistry.add(new Whois());
        CommandRegistry.add(new GetPos());
        CommandRegistry.add(new Depth());
        CommandRegistry.add(new CompassCommand());
        CommandRegistry.add(new Near());
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p && EssConfig.MOTD_ON_JOIN.get()
                    && Perms.has(p, "essentials.motd", Perms.ALL)) {
                List<String> motd = TextFiles.lines("motd.txt");
                TextFiles.sendPage(p.createCommandSourceStack(), motd, 1, "/motd");
            }
        });
    }

    // ---------------------------------------------------------------- list

    static final class ListCommand extends EssCommand {
        ListCommand() {
            super("list", Perms.ALL, "elist", "online", "eonline", "playerlist", "eplayerlist", "plist", "eplist", "who", "ewho");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();
                List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
                players.removeIf(p -> !dev.curatorium.essentials.player.Vanish.canSee(ctx.getSource(), p));
                players.sort(Comparator.comparing(p -> p.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER));
                reply(ctx, Messages.get("list.header", players.size(), server.getMaxPlayers()));
                Map<String, List<ServerPlayer>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (ServerPlayer p : players) {
                    String g = Perms.meta(p).group();
                    groups.computeIfAbsent(g == null ? Messages.raw("list.players") : g, k -> new ArrayList<>()).add(p);
                }
                for (var entry : groups.entrySet()) {
                    MutableComponent names = Component.empty();
                    boolean first = true;
                    for (ServerPlayer p : entry.getValue()) {
                        if (!first) names.append(Messages.get("list.separator"));
                        first = false;
                        if (dev.curatorium.essentials.player.Vanish.isVanished(p)) names.append(Messages.get("list.vanished"));
                        if (Afk.isAfk(p)) names.append(Messages.get("list.afk"));
                        names.append(p.getDisplayName());
                    }
                    reply(ctx, Messages.get("list.group", capitalise(entry.getKey()), names));
                }
                return players.size() + 1;
            }));
        }
    }

    static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------- text files

    /** /motd, /rules [page], /info [chapter] [page], /customtext [chapter] [page]. */
    static final class TextFileCommand extends EssCommand {
        private final String file;
        private final boolean chapters;

        TextFileCommand(String name, String file, boolean chapters, String... aliases) {
            super(name, Perms.ALL, aliases);
            this.file = file;
            this.chapters = chapters;
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> show(ctx, "", 1)))
                    .then(Commands.argument("chapterOrPage", StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                if (chapters) TextFiles.chapterTitles(file).forEach(b::suggest);
                                return b.buildFuture();
                            })
                            .executes(exec(ctx -> {
                                String arg = str(ctx, "chapterOrPage");
                                if (arg.chars().allMatch(Character::isDigit)) return show(ctx, "", Integer.parseInt(arg));
                                return show(ctx, arg, 1);
                            }))
                            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                    .executes(exec(ctx -> show(ctx, str(ctx, "chapterOrPage"), IntegerArgumentType.getInteger(ctx, "page"))))));
        }

        private int show(CommandContext<CommandSourceStack> ctx, String chapter, int page) {
            String cmd = "/" + name();
            List<String> lines;
            if (!chapters) {
                lines = TextFiles.lines(file);
            } else {
                Map<String, List<String>> all = TextFiles.chapters(file);
                if (chapter.isEmpty()) {
                    lines = new ArrayList<>(all.get(""));
                    List<String> titles = TextFiles.chapterTitles(file);
                    if (lines.isEmpty() && !titles.isEmpty()) {
                        reply(ctx, Messages.get("text.chapters", String.join(", ", titles)));
                        return 1;
                    }
                } else {
                    lines = all.get(chapter.toLowerCase(Locale.ROOT));
                    if (lines == null) return fail(ctx, Messages.get("text.noChapter", chapter));
                    cmd += " " + chapter;
                }
            }
            if (lines.isEmpty()) return fail(ctx, Messages.get("text.empty", file));
            TextFiles.sendPage(ctx.getSource(), lines, page, cmd);
            return 1;
        }
    }

    // ---------------------------------------------------------------- help

    /** /help [page|command]: commands the sender can use. */
    static final class Help extends EssCommand {
        Help() {
            super("help", Perms.ALL, "ehelp");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> list(ctx, 1)))
                    .then(Commands.argument("pageOrCommand", StringArgumentType.word()).executes(exec(ctx -> {
                        String arg = str(ctx, "pageOrCommand");
                        if (arg.chars().allMatch(Character::isDigit)) return list(ctx, Integer.parseInt(arg));
                        return usage(ctx, arg.startsWith("/") ? arg.substring(1) : arg);
                    })));
        }

        private int list(CommandContext<CommandSourceStack> ctx, int page) {
            CommandSourceStack src = ctx.getSource();
            List<String> lines = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (CommandNode<CommandSourceStack> node : src.getServer().getCommands().getDispatcher().getRoot().getChildren()) {
                if (!node.canUse(src)) continue;
                EssCommand ess = CommandRegistry.byLabel(node.getName());
                if (ess != null && !ess.name().equals(node.getName())) continue; // an alias
                labels.add(node.getName());
            }
            labels.sort(String.CASE_INSENSITIVE_ORDER);
            for (String label : labels) {
                EssCommand ess = CommandRegistry.byLabel(label);
                String key = ess == null ? null : "help." + ess.name();
                lines.add(key != null && Messages.has(key)
                        ? Messages.raw("help.line").replace("{0}", label).replace("{1}", Messages.raw(key))
                        : Messages.raw("help.lineNoDesc").replace("{0}", label));
            }
            int pages = Math.max(1, (lines.size() + TextFiles.PAGE_LINES - 1) / TextFiles.PAGE_LINES);
            reply(ctx, Messages.get("help.header", Math.min(Math.max(page, 1), pages), pages));
            TextFiles.sendPage(src, lines, page, "/help");
            return 1;
        }

        private int usage(CommandContext<CommandSourceStack> ctx, String label) {
            CommandSourceStack src = ctx.getSource();
            var dispatcher = src.getServer().getCommands().getDispatcher();
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(label.toLowerCase(Locale.ROOT));
            if (node == null || !node.canUse(src)) return fail(ctx, Messages.get("help.unknown", label));
            EssCommand ess = CommandRegistry.byLabel(node.getName());
            if (ess != null && Messages.has("help." + ess.name())) reply(ctx, Messages.get("help.description", Messages.raw("help." + ess.name())));
            Map<CommandNode<CommandSourceStack>, String> usages = dispatcher.getSmartUsage(node, src);
            if (node.getCommand() != null) reply(ctx, Messages.get("help.usage", "/" + node.getName()));
            for (String u : usages.values()) reply(ctx, Messages.get("help.usage", "/" + node.getName() + " " + u));
            if (ess != null && !ess.aliases().isEmpty()) reply(ctx, Messages.get("help.aliases", String.join(", ", ess.aliases())));
            return 1;
        }
    }

    // ---------------------------------------------------------------- ping

    static final class Ping extends EssCommand {
        Ping() {
            super("ping", Perms.ALL, "echo", "eecho", "eping", "pong", "epong");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayer();
                reply(ctx, p == null ? Messages.get("ping.pong") : Messages.get("ping.self", p.connection.latency()));
                return 1;
            })).then(playerArg("player").requires(src -> has(src, "others", Perms.ALL)).executes(exec(ctx -> {
                ServerPlayer t = online(ctx, "player");
                reply(ctx, Messages.get("ping.other", t.getDisplayName(), t.connection.latency()));
                return 1;
            })));
        }
    }

    // ---------------------------------------------------------------- playtime

    static final class Playtime extends EssCommand {
        Playtime() {
            super("playtime", Perms.ALL, "eplaytime");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                reply(ctx, Messages.get("playtime.self", Durations.format(ticks(me) * 50L)));
                return 1;
            })).then(playerArg("player").requires(src -> has(src, "others", Perms.ALL)).executes(exec(ctx -> {
                PlayerLookup.Target t = known(ctx, "player");
                long ticks = t.isOnline() ? ticks(t.online()) : offlineTicks(ctx.getSource().getServer(), t.uuid());
                if (ticks < 0) return fail(ctx, Messages.get("playtime.unknown", t.name()));
                reply(ctx, Messages.get("playtime.other", t.name(), Durations.format(ticks * 50L)));
                return 1;
            })));
        }

        private static long ticks(ServerPlayer p) {
            return p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        }

        /** From the vanilla stats file, -1 if there is none. */
        private static long offlineTicks(MinecraftServer server, UUID id) {
            Path file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json");
            if (!Files.exists(file)) return -1;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject custom = JsonParser.parseReader(r).getAsJsonObject()
                        .getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
                return custom != null && custom.has("minecraft:play_time") ? custom.get("minecraft:play_time").getAsLong() : 0;
            } catch (Exception e) {
                return -1;
            }
        }
    }

    // ---------------------------------------------------------------- seen

    static final class Seen extends EssCommand {
        private static final Pattern IP = Pattern.compile("^[0-9a-fA-F.:]+$");

        Seen() {
            super("seen", Perms.ALL, "eseen", "ealts", "alts");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> {
                String input = str(ctx, "player");
                CommandSourceStack src = ctx.getSource();
                if (IP.matcher(input).matches() && (input.contains(".") || input.contains(":"))) {
                    if (!has(src, "ip", Perms.OP)) return fail(ctx, Messages.get("seen.ipNoPermission"));
                    return byIp(ctx, input);
                }
                PlayerLookup.Target t = known(ctx, "player");
                boolean extra = has(src, "extra", Perms.OP);
                long now = System.currentTimeMillis();
                if (t.isOnline()) {
                    UserData d = UserStore.get(t.online());
                    reply(ctx, Messages.get("seen.online", t.online().getDisplayName(), Durations.format(now - d.lastLogin)));
                    if (Afk.isAfk(t.online())) reply(ctx, Messages.get("seen.afk", Durations.format(Afk.afkFor(t.online()))));
                    if (extra) reply(ctx, Messages.get("seen.ip", d.lastIp));
                    return 1;
                }
                if (!UserStore.exists(t.uuid())) return fail(ctx, Messages.get("seen.never", t.name()));
                UserData d = UserStore.get(t.uuid());
                if (d.lastSeen == 0) return fail(ctx, Messages.get("seen.never", t.name()));
                reply(ctx, Messages.get("seen.offline", t.name(), Durations.format(now - d.lastSeen)));
                if (extra) {
                    reply(ctx, Messages.get("seen.ip", d.lastIp));
                    Location l = d.logoutLocation;
                    if (l != null) reply(ctx, Messages.get("seen.location", l.dimension(), (int) l.x(), (int) l.y(), (int) l.z()));
                }
                return 1;
            })));
        }

        private int byIp(CommandContext<CommandSourceStack> ctx, String ip) {
            List<String> names = new ArrayList<>();
            for (UUID id : UserStore.allKnown()) {
                UserData d = UserStore.get(id);
                if (ip.equals(d.lastIp)) names.add(d.lastName.isEmpty() ? id.toString() : d.lastName);
            }
            if (names.isEmpty()) return fail(ctx, Messages.get("seen.ipNone", ip));
            names.sort(String.CASE_INSENSITIVE_ORDER);
            reply(ctx, Messages.get("seen.ipPlayers", ip, String.join(", ", names)));
            return 1;
        }
    }

    // ---------------------------------------------------------------- whois

    static final class Whois extends EssCommand {
        Whois() {
            super("whois", Perms.OP, "ewhois");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").executes(exec(ctx -> {
                ServerPlayer p = online(ctx, "player");
                UserData d = UserStore.get(p);
                reply(ctx, Messages.get("whois.header", p.getGameProfile().getName()));
                reply(ctx, Messages.get("whois.nick", p.getDisplayName()));
                reply(ctx, Messages.get("whois.uuid", p.getUUID().toString()));
                reply(ctx, Messages.get("whois.health", (int) Math.ceil(p.getHealth()), (int) p.getMaxHealth()));
                reply(ctx, Messages.get("whois.hunger", p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel()));
                reply(ctx, Messages.get("whois.exp", p.experienceLevel, p.totalExperience));
                reply(ctx, Messages.get("whois.location", p.level().dimension().location().toString(),
                        p.getBlockX(), p.getBlockY(), p.getBlockZ()));
                reply(ctx, Messages.get("whois.ip", d.lastIp));
                reply(ctx, Messages.get("whois.gamemode", p.gameMode.getGameModeForPlayer().getName()));
                reply(ctx, Messages.get("whois.op", yesNo(p.server.getPlayerList().isOp(p.getGameProfile()))));
                reply(ctx, Messages.get("whois.fly", yesNo(p.getAbilities().mayfly), yesNo(p.getAbilities().flying)));
                reply(ctx, Messages.get("whois.afk", yesNo(Afk.isAfk(p))));
                if (Wallet.available()) reply(ctx, Messages.get("whois.money", Money.format(Wallet.balance(p))));
                reply(ctx, Messages.get("whois.playtime", Durations.format(p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) * 50L)));
                return 1;
            })));
        }
    }

    static Component yesNo(boolean b) {
        return Messages.get(b ? "yes" : "no");
    }

    // ---------------------------------------------------------------- positions

    static final class GetPos extends EssCommand {
        GetPos() {
            super("getpos", Perms.ALL, "coords", "egetpos", "position", "eposition", "whereami", "ewhereami",
                    "getlocation", "egetlocation", "getloc", "egetloc");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> show(ctx, self(ctx))))
                    .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                            .executes(exec(ctx -> show(ctx, online(ctx, "player")))));
        }

        private int show(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
            reply(ctx, Messages.get("getpos.world", p.level().dimension().location().toString()));
            reply(ctx, Messages.get("getpos.xyz", (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())));
            reply(ctx, Messages.get("getpos.rotation", Math.round(p.getYRot()), Math.round(p.getXRot())));
            return 1;
        }
    }

    static final class Depth extends EssCommand {
        Depth() {
            super("depth", Perms.ALL, "edepth", "height", "eheight");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer p = self(ctx);
                int depth = p.getBlockY() - p.level().getSeaLevel();
                reply(ctx, depth > 0 ? Messages.get("depth.above", depth)
                        : depth < 0 ? Messages.get("depth.below", -depth) : Messages.get("depth.at"));
                return 1;
            }));
        }
    }

    static final class CompassCommand extends EssCommand {
        private static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

        CompassCommand() {
            super("compass", Perms.ALL, "ecompass", "direction", "edirection");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer p = self(ctx);
                // Minecraft yaw: 0 = south, 90 = west. Bearing: 0 = north, 90 = east.
                int bearing = (int) (((p.getYRot() + 180) % 360 + 360) % 360);
                reply(ctx, Messages.get("compass", bearing, DIRS[(int) Math.round(bearing / 45.0) % 8]));
                return 1;
            }));
        }
    }

    /** /near [radius], /near <player> [radius] */
    static final class Near extends EssCommand {
        Near() {
            super("near", Perms.OP, "enear", "nearby", "enearby");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> show(ctx, self(ctx), EssConfig.NEAR_RADIUS.get())))
                    .then(playerArg("playerOrRadius").executes(exec(ctx -> {
                                String arg = str(ctx, "playerOrRadius");
                                if (arg.chars().allMatch(Character::isDigit) && !arg.isEmpty()) {
                                    return show(ctx, self(ctx), (int) Math.min(Integer.MAX_VALUE, Long.parseLong(arg)));
                                }
                                return other(ctx, EssConfig.NEAR_RADIUS.get());
                            }))
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                    .executes(exec(ctx -> other(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))));
        }

        private int other(CommandContext<CommandSourceStack> ctx, int radius) throws CommandSyntaxException {
            if (!has(ctx.getSource(), "others", Perms.OP)) return fail(ctx, Messages.get("command.noPermission"));
            return show(ctx, online(ctx, "playerOrRadius"), radius);
        }

        private int show(CommandContext<CommandSourceStack> ctx, ServerPlayer center, int radius) {
            int max = EssConfig.NEAR_MAX.get();
            if (radius > max && !has(ctx.getSource(), "maxexempt", Perms.ADMIN)) radius = max;
            double r2 = (double) radius * radius;
            List<ServerPlayer> near = new ArrayList<>();
            for (ServerPlayer p : center.serverLevel().players()) {
                if (p != center && p.distanceToSqr(center) <= r2
                        && dev.curatorium.essentials.player.Vanish.canSee(ctx.getSource(), p)) near.add(p);
            }
            near.sort(Comparator.comparingDouble(p -> p.distanceToSqr(center)));
            if (near.isEmpty()) {
                reply(ctx, Messages.get("near.none", radius));
                return 1;
            }
            MutableComponent list = Component.empty();
            for (int i = 0; i < near.size(); i++) {
                if (i > 0) list.append(Messages.get("list.separator"));
                ServerPlayer p = near.get(i);
                list.append(Messages.get("near.entry", p.getDisplayName(), (int) Math.round(Math.sqrt(p.distanceToSqr(center)))));
            }
            reply(ctx, Messages.get("near.list", radius, list));
            return 1;
        }
    }

    private InfoCommands() {}
}
