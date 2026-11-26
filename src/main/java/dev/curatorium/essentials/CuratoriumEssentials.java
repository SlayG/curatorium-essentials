package dev.curatorium.essentials;

import dev.curatorium.essentials.chat.Chat;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.commands.EssentialsCommand;
import dev.curatorium.essentials.commands.InfoCommands;
import dev.curatorium.essentials.commands.MessagingCommands;
import dev.curatorium.essentials.commands.PlayerCommands;
import dev.curatorium.essentials.player.Afk;
import dev.curatorium.essentials.player.Names;
import dev.curatorium.essentials.text.TextFiles;
import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Curatorium Essentials: a server-only NeoForge port of EssentialsX.
 *
 * <p>Loaded on dedicated servers only. It registers nothing a vanilla client would need to know
 * about (no items, blocks, menus or network channels), so players join without it.
 */
@Mod(value = CuratoriumEssentials.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class CuratoriumEssentials {
    public static final String MOD_ID = "curatorium_essentials";
    public static final Logger LOGGER = LoggerFactory.getLogger("Curatorium Essentials");

    private static MinecraftServer server;
    private static String version = "unknown";

    public CuratoriumEssentials(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.COMMON, EssConfig.SPEC, "curatorium_essentials/config.toml");
        modBus.addListener(ModConfigEvent.Loading.class, e -> onConfig(e.getConfig()));
        modBus.addListener(ModConfigEvent.Reloading.class, e -> onConfig(e.getConfig()));

        Messages.load();

        CommandRegistry.add(new EssentialsCommand());
        MessagingCommands.register();
        PlayerCommands.register();
        InfoCommands.register();

        IEventBus bus = NeoForge.EVENT_BUS;
        Chat.registerEvents(bus);
        Names.registerEvents(bus);
        Afk.registerEvents(bus);
        PlayerCommands.registerEvents(bus);
        InfoCommands.registerEvents(bus);
        bus.addListener(RegisterCommandsEvent.class, e -> CommandRegistry.registerAll(e.getDispatcher()));
        bus.addListener(ServerAboutToStartEvent.class, e -> {
            server = e.getServer();
            UserStore.open(server);
        });
        bus.addListener(ServerStartedEvent.class, e -> Perms.onServerStarted(e.getServer()));
        bus.addListener(ServerStoppingEvent.class, e -> UserStore.close());
        bus.addListener(ServerStoppedEvent.class, e -> server = null);
        UserStore.registerEvents(bus);
    }

    private static void onConfig(ModConfig config) {
        if (config.getSpec() == EssConfig.SPEC) EssConfig.bake();
    }

    /** The running server, or null before it starts / after it stops. */
    public static MinecraftServer server() {
        return server;
    }

    public static String version() {
        return version;
    }

    /** Re-reads the files the mod owns, then resends command trees so permission changes show. */
    public static void reload() {
        EssConfig.bake();
        Messages.load();
        TextFiles.reload();
        if (server != null) {
            server.getPlayerList().getPlayers().forEach(p -> {
                server.getCommands().sendCommands(p);
                Names.refresh(p);
            });
        }
    }
}
