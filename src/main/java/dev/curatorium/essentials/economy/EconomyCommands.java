package dev.curatorium.essentials.economy;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.PlayerLookup;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * /balance, /pay, /paytoggle, /payconfirmtoggle and /eco. Money is only the coins players
 * carry; paying someone who is offline holds the coins until their next login.
 */
public final class EconomyCommands {
    public static void register() {
        CommandRegistry.add(new Balance());
        CommandRegistry.add(new Pay());
        CommandRegistry.add(new PayToggle());
        CommandRegistry.add(new PayConfirmToggle());
        CommandRegistry.add(new Eco());
    }

    public static void registerEvents(IEventBus bus) {
        // LOWEST: after the player store has recorded the login, so a first join can be recognised.
        bus.addListener(EventPriority.LOWEST, PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p) || !Wallet.available()) return;
            UserData d = UserStore.get(p);
            if (d.firstJoin == d.lastLogin && EssConfig.STARTING_COINS.get() > 0) {
                Wallet.give(p, EssConfig.STARTING_COINS.get(), "starting coins");
                p.sendSystemMessage(Messages.get("economy.startingCoins", Money.format(EssConfig.STARTING_COINS.get())));
            }
            java.util.List<String> notes = java.util.List.copyOf(d.escrowNotes);
            long held = Wallet.releaseEscrow(p);
            if (held > 0) {
                p.sendSystemMessage(Messages.get("pay.heldReceived", Money.format(held)));
                for (String note : notes) p.sendSystemMessage(Messages.get("pay.heldNote", note));
            }
        });
    }

    /** Reads an amount typed in spurs or coins, or throws the "not an amount" error. */
    static long amount(String input) throws CommandSyntaxException {
        OptionalLong v = Money.parse(input);
        if (v.isEmpty()) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Messages.get("economy.badAmount", input)).create();
        return v.getAsLong();
    }

    static boolean unavailable(CommandContext<CommandSourceStack> ctx) {
        if (Wallet.available()) return false;
        ctx.getSource().sendFailure(Messages.get("economy.unavailable"));
        return true;
    }

    /** /balance [player]: the coins someone carries. */
    static final class Balance extends EssCommand {
        Balance() {
            super("balance", Perms.ALL, "bal", "ebal", "ebalance", "money", "emoney", "coins", "ecoins");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                if (unavailable(ctx)) return 0;
                long held = UserStore.get(p).escrow;
                reply(ctx, ctx.getSource().getPlayer() == p ? Messages.get("balance.self", Money.format(Wallet.balance(p)))
                        : Messages.get("balance.other", p.getDisplayName(), Money.format(Wallet.balance(p))));
                if (held > 0) reply(ctx, Messages.get("balance.held", Money.format(held)));
                return 1;
            });
        }
    }

    /**
     * /pay <player> <amount>: moves coins from your inventory into theirs (or, if they're
     * offline, holds them until they next log in). Amounts are spurs or coins ("3crown 2cog").
     */
    static final class Pay extends EssCommand {
        private record Pending(String input, long until) {}

        private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

        Pay() {
            super("pay", Perms.ALL, "epay");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(playerArg("player").then(textArg("amount").executes(exec(ctx -> {
                if (unavailable(ctx)) return 0;
                ServerPlayer me = self(ctx);
                PlayerLookup.Target to = known(ctx, "player");
                if (to.uuid().equals(me.getUUID())) return fail(ctx, Messages.get("pay.self"));
                long spurs = amount(str(ctx, "amount"));
                if (spurs < EssConfig.PAY_MIN.get()) return fail(ctx, Messages.get("pay.tooSmall", Money.format(EssConfig.PAY_MIN.get())));
                UserData target = UserStore.get(to.uuid());
                if (!target.acceptPayments) return fail(ctx, Messages.get("pay.refused", to.name()));
                if (!Wallet.canAfford(me, spurs)) return fail(ctx, Messages.get("pay.cantAfford", Money.format(spurs)));
                if (confirmOn(me)) {
                    String input = ctx.getInput();
                    long now = System.currentTimeMillis();
                    Pending p = pending.get(me.getUUID());
                    if (p == null || !p.input().equals(input) || p.until() < now) {
                        pending.put(me.getUUID(), new Pending(input, now + 30_000));
                        reply(ctx, Messages.get("pay.confirm", Money.format(spurs), to.name(), "/" + input));
                        return 0;
                    }
                    pending.remove(me.getUUID());
                }
                String from = me.getGameProfile().getName();
                if (!Wallet.take(me, spurs, "/pay to " + to.name())) return fail(ctx, Messages.get("pay.cantAfford", Money.format(spurs)));
                if (to.isOnline()) {
                    Wallet.give(to.online(), spurs, "/pay from " + from);
                    to.online().sendSystemMessage(Messages.get("pay.received", Money.format(spurs), me.getDisplayName()));
                    reply(ctx, Messages.get("pay.sent", Money.format(spurs), to.online().getDisplayName()));
                } else {
                    Wallet.escrow(to.uuid(), to.name(), spurs, Messages.raw("pay.heldFrom").replace("{0}", Money.format(spurs)).replace("{1}", from));
                    reply(ctx, Messages.get("pay.sentHeld", Money.format(spurs), to.name()));
                }
                return 1;
            }))));
        }

        static boolean confirmOn(ServerPlayer p) {
            Boolean b = UserStore.get(p).payConfirm;
            return b != null ? b : EssConfig.PAY_CONFIRM.get();
        }
    }

    static final class PayToggle extends EssCommand {
        PayToggle() {
            super("paytoggle", Perms.ALL, "epaytoggle", "payoff", "epayoff", "payon", "epayon");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                UserData d = UserStore.get(p);
                String label = ctx.getNodes().get(0).getNode().getName();
                d.acceptPayments = label.endsWith("off") ? false : label.endsWith("on") ? true : !d.acceptPayments;
                d.markDirty();
                Component state = Messages.get(d.acceptPayments ? "toggle.on" : "toggle.off");
                p.sendSystemMessage(Messages.get("paytoggle.state", state));
                tellOther(ctx, p, Messages.get("toggle.other", Messages.get("paytoggle.state", state), p.getDisplayName()));
                return 1;
            });
        }
    }

    static final class PayConfirmToggle extends EssCommand {
        PayConfirmToggle() {
            super("payconfirmtoggle", Perms.ALL, "epayconfirmtoggle");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> {
                ServerPlayer me = self(ctx);
                UserData d = UserStore.get(me);
                d.payConfirm = !Pay.confirmOn(me);
                d.markDirty();
                reply(ctx, Messages.get("payconfirm.state", Messages.get(d.payConfirm ? "toggle.on" : "toggle.off")));
                return 1;
            }));
        }
    }

    /**
     * /eco give <player> <amount> mints coins into their inventory (held until login if they're
     * offline); /eco take <player> <amount> removes coins (online players only). There's no
     * set/reset: there are no balances to set.
     */
    static final class Eco extends EssCommand {
        Eco() {
            super("eco", Perms.ADMIN, "eeco", "economy", "eeconomy");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.then(Commands.literal("give").then(playerArg("player").then(textArg("amount").executes(exec(ctx -> {
                        if (unavailable(ctx)) return 0;
                        PlayerLookup.Target t = known(ctx, "player");
                        long spurs = amount(str(ctx, "amount"));
                        String by = ctx.getSource().getTextName();
                        if (t.isOnline()) {
                            Wallet.give(t.online(), spurs, "/eco give by " + by);
                            t.online().sendSystemMessage(Messages.get("eco.received", Money.format(spurs)));
                            reply(ctx, Messages.get("eco.given", Money.format(spurs), t.name()));
                        } else {
                            Wallet.escrow(t.uuid(), t.name(), spurs, Messages.raw("eco.heldNote").replace("{0}", Money.format(spurs)));
                            reply(ctx, Messages.get("eco.givenHeld", Money.format(spurs), t.name()));
                        }
                        return 1;
                    })))))
                    .then(Commands.literal("take").then(playerArg("player").then(textArg("amount").executes(exec(ctx -> {
                        if (unavailable(ctx)) return 0;
                        ServerPlayer p = online(ctx, "player");
                        long spurs = amount(str(ctx, "amount"));
                        if (!Wallet.take(p, spurs, "/eco take by " + ctx.getSource().getTextName())) {
                            return fail(ctx, Messages.get("eco.notEnough", p.getDisplayName(), Money.format(Wallet.balance(p))));
                        }
                        p.sendSystemMessage(Messages.get("eco.taken", Money.format(spurs)));
                        reply(ctx, Messages.get("eco.tookFrom", Money.format(spurs), p.getDisplayName()));
                        return 1;
                    })))));
        }
    }

    private EconomyCommands() {}
}
