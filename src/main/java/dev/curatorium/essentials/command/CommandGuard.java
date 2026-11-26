package dev.curatorium.essentials.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.economy.Money;
import dev.curatorium.essentials.economy.Wallet;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Durations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Runs a command body with EssentialsX's cooldowns and command costs. A player is checked
 * before the command runs (still cooling down? can afford it?) but only charged and put on
 * cooldown if the command succeeds (returns more than 0).
 */
final class CommandGuard {
    static int run(EssCommand command, CommandContext<CommandSourceStack> ctx, Command<CommandSourceStack> body)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return body.run(ctx);

        String name = command.name();
        long cooldown = EssConfig.cooldown(name);
        boolean cooled = cooldown > 0
                && !Perms.has(player, "essentials.commandcooldowns.bypass", Perms.ADMIN)
                && !Perms.has(player, "essentials.commandcooldowns.bypass." + name, Perms.ADMIN);
        long now = System.currentTimeMillis();
        UserData data = UserStore.get(player);
        if (cooled) {
            Long until = data.cooldowns.get(name);
            if (until != null && until > now) {
                ctx.getSource().sendFailure(Messages.get("command.cooldown", Durations.format(until - now)));
                return 0;
            }
        }

        long cost = EssConfig.cost(name);
        boolean pays = cost > 0
                && !Perms.has(player, "essentials.nocommandcost.all", Perms.ADMIN)
                && !Perms.has(player, "essentials.nocommandcost." + name, Perms.ADMIN);
        if (pays) {
            if (!Wallet.available()) {
                ctx.getSource().sendFailure(Messages.get("economy.unavailable"));
                return 0;
            }
            if (!Wallet.canAfford(player, cost)) {
                ctx.getSource().sendFailure(Messages.get("command.cost.cantAfford", Money.format(cost)));
                return 0;
            }
        }

        int result = body.run(ctx);
        if (result <= 0) return result;

        if (pays) {
            // The command may have spent coins itself; if they can no longer pay, it stays free this time.
            if (Wallet.take(player, cost, "/" + name)) {
                ctx.getSource().sendSuccess(() -> Messages.get("command.cost.paid", Money.format(cost)), false);
            }
        }
        if (cooled) {
            data.cooldowns.put(name, now + cooldown * 1000L);
            data.markDirty();
        }
        return result;
    }

    private CommandGuard() {}
}
