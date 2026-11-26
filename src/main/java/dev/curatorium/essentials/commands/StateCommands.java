package dev.curatorium.essentials.commands;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.chat.Chat;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.moderation.Mutes;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.player.Vanish;
import dev.curatorium.essentials.text.Messages;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** God mode, flight, game modes, healing and the other "change a player" commands, plus /vanish. */
public final class StateCommands {
    public static void register() {
        CommandRegistry.add(new VanishCommand());
        CommandRegistry.add(new God());
        CommandRegistry.add(new Fly());
        CommandRegistry.add(new GameModeCommand("gamemode", null, "gm", "egamemode", "egm"));
        CommandRegistry.add(new GameModeCommand("gmc", GameType.CREATIVE, "egmc", "creative", "ecreative"));
        CommandRegistry.add(new GameModeCommand("gms", GameType.SURVIVAL, "egms", "survival", "esurvival"));
        CommandRegistry.add(new GameModeCommand("gma", GameType.ADVENTURE, "egma", "adventure", "eadventure"));
        CommandRegistry.add(new GameModeCommand("gmsp", GameType.SPECTATOR, "egmsp", "spectator", "espectator"));
        CommandRegistry.add(new Heal());
        CommandRegistry.add(new Feed());
        CommandRegistry.add(new Speed());
        CommandRegistry.add(new Exp());
        CommandRegistry.add(new Kill());
        CommandRegistry.add(new Suicide());
        CommandRegistry.add(new Rest());
        CommandRegistry.add(new Burn());
        CommandRegistry.add(new Sudo());
    }

    private static int ticks;

    public static void registerEvents(IEventBus bus) {
        bus.addListener(LivingIncomingDamageEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p && UserStore.get(p).god) e.setCanceled(true);
        });
        bus.addListener(ServerTickEvent.Post.class, e -> {
            if (++ticks < 20) return;
            ticks = 0;
            for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) keepState(p);
        });
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) keepState(p);
        });
    }

    /** God players stay fed; fly players keep flight after a game-mode change, respawn or dimension change. */
    private static void keepState(ServerPlayer p) {
        UserData d = UserStore.get(p);
        if (d.god) {
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(Math.max(p.getFoodData().getSaturationLevel(), 5f));
            p.clearFire();
        }
        if (d.fly && !p.getAbilities().mayfly) {
            p.getAbilities().mayfly = true;
            p.onUpdateAbilities();
        }
    }

    static Component state(boolean on) {
        return Messages.get(on ? "toggle.on" : "toggle.off");
    }

    /** "on"/"off"/"true"/"false"/"enable"/"disable", or null to toggle. */
    static Boolean parseSwitch(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "enabled", "1" -> true;
            case "off", "false", "disable", "disabled", "0" -> false;
            default -> null;
        };
    }

    // ---------------------------------------------------------------- vanish, god, fly

    /** A player toggle: /cmd, /cmd on|off, /cmd <player> [on|off] (others need essentials.<cmd>.others). */
    private abstract static class PlayerToggle extends EssCommand {
        PlayerToggle(String name, int level, String... aliases) {
            super(name, level, aliases);
        }

        abstract boolean get(ServerPlayer p);

        abstract void set(ServerPlayer p, boolean on);

        abstract String key();

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> apply(ctx, self(ctx), null)))
                    .then(Commands.argument("player or state", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(onlineAnd(c.getSource(), "on", "off"), b))
                            .executes(exec(ctx -> {
                                String arg = str(ctx, "player or state");
                                Boolean sw = parseSwitch(arg);
                                if (sw != null) return apply(ctx, self(ctx), sw);
                                if (!has(ctx.getSource(), "others", Perms.OP)) return fail(ctx, Messages.get("command.noPermission"));
                                return apply(ctx, online(ctx, "player or state"), null);
                            }))
                            .then(Commands.argument("state", StringArgumentType.word())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("on", "off"), b))
                                    .requires(src -> has(src, "others", Perms.OP))
                                    .executes(exec(ctx -> {
                                        Boolean sw = parseSwitch(str(ctx, "state"));
                                        if (sw == null) return fail(ctx, Messages.get("toggle.badState", str(ctx, "state")));
                                        return apply(ctx, online(ctx, "player or state"), sw);
                                    }))));
        }

        private int apply(CommandContext<CommandSourceStack> ctx, ServerPlayer target, Boolean wanted) {
            boolean on = wanted != null ? wanted : !get(target);
            set(target, on);
            target.sendSystemMessage(Messages.get(key(), state(on)));
            tellOther(ctx, target, Messages.get("toggle.other", Messages.get(key(), state(on)), target.getDisplayName()));
            return 1;
        }
    }

    static List<String> onlineAnd(CommandSourceStack src, String... extra) {
        List<String> out = new ArrayList<>(List.of(extra));
        for (ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
            if (Vanish.canSee(src, p)) out.add(p.getGameProfile().getName());
        }
        return out;
    }

    static final class VanishCommand extends PlayerToggle {
        VanishCommand() {
            super("vanish", Perms.OP, "evanish", "v", "ev");
        }

        boolean get(ServerPlayer p) { return Vanish.isVanished(p); }

        void set(ServerPlayer p, boolean on) { Vanish.set(p, on); }

        String key() { return "vanish.state"; }
    }

    static final class God extends PlayerToggle {
        God() {
            super("god", Perms.OP, "egod", "godmode", "egodmode", "tgm", "etgm");
        }

        boolean get(ServerPlayer p) { return UserStore.get(p).god; }

        void set(ServerPlayer p, boolean on) {
            UserData d = UserStore.get(p);
            d.god = on;
            d.markDirty();
            if (on) {
                p.setHealth(p.getMaxHealth());
                keepState(p);
            }
        }

        String key() { return "god.state"; }
    }

    static final class Fly extends PlayerToggle {
        Fly() {
            super("fly", Perms.OP, "efly");
        }

        boolean get(ServerPlayer p) { return p.getAbilities().mayfly && !p.isCreative() && !p.isSpectator() || UserStore.get(p).fly; }

        void set(ServerPlayer p, boolean on) {
            UserData d = UserStore.get(p);
            d.fly = on;
            d.markDirty();
            if (p.isCreative() || p.isSpectator()) return; // those modes fly anyway
            p.getAbilities().mayfly = on;
            if (!on) {
                p.getAbilities().flying = false;
                p.fallDistance = 0; // no fall damage from switching off mid-air
            }
            p.onUpdateAbilities();
        }

        String key() { return "fly.state"; }
    }

    // ---------------------------------------------------------------- gamemode

    static GameType parseMode(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival", "surv" -> GameType.SURVIVAL;
            case "1", "c", "creative", "creat" -> GameType.CREATIVE;
            case "2", "a", "adventure", "adv" -> GameType.ADVENTURE;
            case "3", "sp", "spec", "spectator" -> GameType.SPECTATOR;
            default -> null;
        };
    }

    /**
     * /gamemode <mode> [players] (vanilla's form, selectors work), and the shortcuts /gmc /gms
     * /gma /gmsp [players]. Each mode needs essentials.gamemode.<mode> (default op), others
     * essentials.gamemode.others.
     */
    static final class GameModeCommand extends EssCommand {
        private final GameType fixed;

        GameModeCommand(String name, GameType fixed, String... aliases) {
            super(name, fixed == null ? "essentials.gamemode" : "essentials.gamemode." + fixed.getName(), Perms.OP, aliases);
            this.fixed = fixed;
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            var targets = Commands.argument("players", EntityArgument.players())
                    .requires(src -> Perms.has(src, "essentials.gamemode.others", Perms.OP));
            if (fixed != null) {
                root.executes(exec(ctx -> set(ctx, fixed, List.of(self(ctx)))))
                        .then(targets.executes(exec(ctx -> set(ctx, fixed, EntityArgument.getPlayers(ctx, "players")))));
                return;
            }
            root.then(Commands.argument("mode", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("survival", "creative", "adventure", "spectator"), b))
                    .executes(exec(ctx -> set(ctx, mode(ctx), List.of(self(ctx)))))
                    .then(targets.executes(exec(ctx -> set(ctx, mode(ctx), EntityArgument.getPlayers(ctx, "players"))))));
        }

        private static GameType mode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            GameType mode = parseMode(str(ctx, "mode"));
            if (mode == null) throw error(Messages.get("gamemode.unknown", str(ctx, "mode")));
            return mode;
        }

        private int set(CommandContext<CommandSourceStack> ctx, GameType mode, Collection<ServerPlayer> targets) {
            if (!Perms.has(ctx.getSource(), "essentials.gamemode." + mode.getName(), Perms.OP)) {
                return fail(ctx, Messages.get("gamemode.noPermission", mode.getShortDisplayName()));
            }
            int n = 0;
            for (ServerPlayer p : targets) {
                if (p.setGameMode(mode)) n++;
                p.sendSystemMessage(Messages.get("gamemode.set", mode.getLongDisplayName()));
                tellOther(ctx, p, Messages.get("gamemode.setOther", p.getDisplayName(), mode.getLongDisplayName()));
            }
            return Math.max(n, 1);
        }
    }

    // ---------------------------------------------------------------- heal, feed, rest

    static final class Heal extends EssCommand {
        Heal() {
            super("heal", Perms.OP, "eheal");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                if (p.isDeadOrDying()) return fail(ctx, Messages.get("heal.dead", p.getDisplayName()));
                p.setHealth(p.getMaxHealth());
                p.getFoodData().setFoodLevel(20);
                p.getFoodData().setSaturation(5f);
                p.clearFire();
                p.setTicksFrozen(0);
                p.removeAllEffects();
                p.sendSystemMessage(Messages.get("heal.healed"));
                tellOther(ctx, p, Messages.get("heal.healedOther", p.getDisplayName()));
                return 1;
            });
        }
    }

    static final class Feed extends EssCommand {
        Feed() {
            super("feed", Perms.OP, "efeed", "eat", "eeat");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                p.getFoodData().setFoodLevel(20);
                p.getFoodData().setSaturation(10f);
                p.getFoodData().setExhaustion(0);
                p.sendSystemMessage(Messages.get("feed.fed"));
                tellOther(ctx, p, Messages.get("feed.fedOther", p.getDisplayName()));
                return 1;
            });
        }
    }

    static final class Rest extends EssCommand {
        Rest() {
            super("rest", Perms.OP, "erest", "rested", "erested");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                p.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
                p.sendSystemMessage(Messages.get("rest.rested"));
                tellOther(ctx, p, Messages.get("rest.restedOther", p.getDisplayName()));
                return 1;
            });
        }
    }

    // ---------------------------------------------------------------- speed

    /** /speed [walk|fly] <0-10> [player]: 1 is normal, 10 the fastest. */
    static final class Speed extends EssCommand {
        private static final float WALK = 0.1f, FLY = 0.05f, MAX = 0.5f;

        Speed() {
            super("speed", Perms.OP, "espeed", "flyspeed", "eflyspeed", "fspeed", "efspeed", "walkspeed", "ewalkspeed", "wspeed", "ewspeed");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            var speed = Commands.argument("speed", FloatArgumentType.floatArg(0, 10));
            root.then(speed.executes(exec(ctx -> set(ctx, null, self(ctx))))
                    .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                            .executes(exec(ctx -> set(ctx, null, online(ctx, "player"))))));
            for (String type : new String[] {"walk", "fly"}) {
                root.then(Commands.literal(type).then(Commands.argument("speed", FloatArgumentType.floatArg(0, 10))
                        .executes(exec(ctx -> set(ctx, type.equals("fly"), self(ctx))))
                        .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                                .executes(exec(ctx -> set(ctx, type.equals("fly"), online(ctx, "player")))))));
            }
        }

        static float real(float speed, float base) {
            if (speed < 1) return base * speed;
            return base + (speed - 1) / 9f * (MAX - base);
        }

        private int set(CommandContext<CommandSourceStack> ctx, Boolean fly, ServerPlayer p) {
            String label = ctx.getNodes().get(0).getNode().getName();
            if (fly == null) {
                if (label.contains("fly") || label.contains("fspeed")) fly = true;
                else if (label.contains("walk") || label.contains("wspeed")) fly = false;
                else fly = p.getAbilities().flying;
            }
            float speed = FloatArgumentType.getFloat(ctx, "speed");
            if (fly) {
                p.getAbilities().setFlyingSpeed(real(speed, FLY));
            } else {
                float v = real(speed, WALK);
                p.getAbilities().setWalkingSpeed(v);
                p.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(v);
            }
            p.onUpdateAbilities();
            Component type = Messages.get(fly ? "speed.fly" : "speed.walk");
            p.sendSystemMessage(Messages.get("speed.set", type, speed));
            tellOther(ctx, p, Messages.get("speed.setOther", type, speed, p.getDisplayName()));
            return 1;
        }
    }

    // ---------------------------------------------------------------- exp

    /** /exp [show <player>], /exp give|set|take <player> <amount>[L]: amounts are points, or levels with an L. */
    static final class Exp extends EssCommand {
        Exp() {
            super("exp", Perms.OP, "eexp", "xp", "exe");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> show(ctx, self(ctx))))
                    .then(Commands.literal("show").executes(exec(ctx -> show(ctx, self(ctx))))
                            .then(playerArg("player").requires(src -> has(src, "others", Perms.OP))
                                    .executes(exec(ctx -> show(ctx, online(ctx, "player"))))));
            for (String action : new String[] {"give", "set", "take"}) {
                root.then(Commands.literal(action).requires(src -> has(src, action, Perms.OP))
                        .then(playerArg("player").then(Commands.argument("amount", StringArgumentType.word())
                                .executes(exec(ctx -> change(ctx, action, online(ctx, "player"), str(ctx, "amount")))))));
            }
        }

        private int show(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
            reply(ctx, Messages.get("exp.show", p.getDisplayName(), totalPoints(p), p.experienceLevel,
                    Math.round(p.getXpNeededForNextLevel() * (1 - p.experienceProgress))));
            return 1;
        }

        /** Points needed to go from level {@code level} to the next (vanilla's formula). */
        static int needed(int level) {
            return level >= 30 ? 112 + (level - 30) * 9 : level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
        }

        /** All the player's experience points, worked out from level and progress (totalExperience isn't kept up to date by level changes). */
        static int totalPoints(ServerPlayer p) {
            long total = 0;
            for (int l = 0; l < p.experienceLevel; l++) total += needed(l);
            return (int) Math.min(Integer.MAX_VALUE, total + Math.round(p.experienceProgress * p.getXpNeededForNextLevel()));
        }

        private int change(CommandContext<CommandSourceStack> ctx, String action, ServerPlayer p, String amount) {
            String a = amount.toLowerCase(Locale.ROOT);
            boolean levels = a.endsWith("l");
            int n;
            try {
                n = Integer.parseInt(levels ? a.substring(0, a.length() - 1) : a);
            } catch (NumberFormatException e) {
                return fail(ctx, Messages.get("exp.badAmount", amount));
            }
            if (n < 0) return fail(ctx, Messages.get("exp.badAmount", amount));
            switch (action) {
                case "give" -> { if (levels) p.giveExperienceLevels(n); else p.giveExperiencePoints(n); }
                case "take" -> {
                    if (levels) {
                        p.giveExperienceLevels(-n);
                    } else {
                        int left = Math.max(0, totalPoints(p) - n);
                        p.setExperienceLevels(0);
                        p.setExperiencePoints(0);
                        p.giveExperiencePoints(left);
                    }
                }
                default -> {
                    if (levels) {
                        p.setExperienceLevels(n);
                    } else {
                        p.setExperienceLevels(0);
                        p.setExperiencePoints(0);
                        p.totalExperience = 0;
                        p.giveExperiencePoints(n);
                    }
                }
            }
            return show(ctx, p);
        }
    }

    // ---------------------------------------------------------------- kill, suicide, burn

    /** /kill [targets]: vanilla's form (selectors work); players with essentials.kill.exempt are spared. */
    static final class Kill extends EssCommand {
        Kill() {
            super("kill", Perms.OP, "ekill");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> kill(ctx, List.of(self(ctx)))))
                    .then(Commands.argument("targets", EntityArgument.entities())
                            .executes(exec(ctx -> kill(ctx, EntityArgument.getEntities(ctx, "targets")))));
        }

        private int kill(CommandContext<CommandSourceStack> ctx, Collection<? extends Entity> targets) {
            int n = 0;
            for (Entity e : targets) {
                if (e instanceof ServerPlayer p && p != ctx.getSource().getPlayer()
                        && Perms.has(p, "essentials.kill.exempt", Perms.ADMIN)) {
                    ctx.getSource().sendFailure(Messages.get("kill.exempt", p.getDisplayName()));
                    continue;
                }
                e.kill();
                n++;
            }
            if (n == 0) return 0;
            int killed = n;
            if (targets.size() == 1) reply(ctx, Messages.get("kill.one", targets.iterator().next().getDisplayName()));
            else reply(ctx, Messages.get("kill.many", killed));
            return n;
        }
    }

    static final class Suicide extends EssCommand {
        Suicide() {
            super("suicide", Perms.ALL, "esuicide");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                self(ctx).kill();
                return 1;
            }));
        }
    }

    /** /burn <player> <seconds> */
    static final class Burn extends EssCommand {
        Burn() {
            super("burn", Perms.OP, "eburn", "ignite", "eignite");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600)).executes(exec(ctx -> {
                ServerPlayer p = online(ctx, "player");
                int s = IntegerArgumentType.getInteger(ctx, "seconds");
                p.igniteForSeconds(s);
                reply(ctx, Messages.get("burn.done", p.getDisplayName(), s));
                return 1;
            }))));
        }
    }

    // ---------------------------------------------------------------- sudo

    /** /sudo <player> <command> runs a command as them; /sudo <player> c:<message> makes them chat. */
    static final class Sudo extends EssCommand {
        Sudo() {
            super("sudo", Perms.ADMIN, "esudo");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").then(textArg("command").executes(exec(ctx -> {
                ServerPlayer p = online(ctx, "player");
                if (p != ctx.getSource().getPlayer() && Perms.has(p, "essentials.sudo.exempt", Perms.ADMIN)) {
                    return fail(ctx, Messages.get("sudo.exempt", p.getDisplayName()));
                }
                String text = str(ctx, "command").trim();
                if (text.regionMatches(true, 0, "c:", 0, 2)) {
                    String msg = text.substring(2).trim();
                    if (msg.isEmpty()) return fail(ctx, Messages.get("sudo.usage"));
                    if (!Mutes.checkMuted(p)) Chat.send(p, msg);
                    reply(ctx, Messages.get("sudo.chat", p.getDisplayName(), msg));
                    return 1;
                }
                String command = text.startsWith("/") ? text.substring(1) : text;
                reply(ctx, Messages.get("sudo.command", p.getDisplayName(), command));
                p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
                return 1;
            }))));
        }
    }

    private StateCommands() {}
}
