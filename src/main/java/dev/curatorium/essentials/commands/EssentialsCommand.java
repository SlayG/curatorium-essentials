package dev.curatorium.essentials.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.curatorium.essentials.CuratoriumEssentials;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.economy.Wallet;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.fml.ModList;

/** {@code /essentials [version|reload]} (aliases {@code /ess}, {@code /eessentials}). */
public final class EssentialsCommand extends EssCommand {
    public EssentialsCommand() {
        super("essentials", Perms.OP, "ess", "eessentials");
    }

    @Override
    protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.executes(exec(EssentialsCommand::version))
                .then(Commands.literal("version").executes(exec(EssentialsCommand::version)))
                .then(Commands.literal("reload")
                        .requires(src -> has(src, "reload", Perms.ADMIN))
                        .executes(exec(ctx -> {
                            CuratoriumEssentials.reload();
                            reply(ctx, Messages.get("essentials.reloaded"));
                            return 1;
                        })));
    }

    private static int version(CommandContext<CommandSourceStack> ctx) {
        reply(ctx, Messages.get("essentials.version", CuratoriumEssentials.version()));
        reply(ctx, Messages.get("essentials.version.luckperms", Perms.luckPermsActive()
                ? modVersion("luckperms") : Messages.raw("essentials.notInstalled")));
        reply(ctx, Messages.get("essentials.version.numismatics", Wallet.available()
                ? modVersion("numismatics") : Messages.raw("essentials.notInstalled")));
        return 1;
    }

    private static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("?");
    }
}
