package dev.curatorium.essentials.util;

import dev.curatorium.essentials.perm.Perms;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Messages for staff: everyone online with a node, plus the console. */
public final class Notify {
    public static void staff(MinecraftServer server, String node, Component message) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Perms.has(p, node, Perms.OP)) p.sendSystemMessage(message);
        }
        server.sendSystemMessage(message);
        dev.curatorium.essentials.discord.DiscordCommands.staffNotice(node, message);
    }

    private Notify() {}
}
