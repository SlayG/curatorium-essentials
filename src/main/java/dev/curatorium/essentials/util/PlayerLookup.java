package dev.curatorium.essentials.util;

import dev.curatorium.essentials.data.UserStore;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.curatorium.essentials.player.Names;
import dev.curatorium.essentials.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Finding players by name for commands that take a plain string, so offline players and
 * partial names work (vanilla's player argument only matches online players exactly).
 */
public final class PlayerLookup {
    /** A found player: always a UUID and name, plus the player if they're online. */
    public record Target(UUID uuid, String name, ServerPlayer online) {
        public boolean isOnline() {
            return online != null;
        }
    }

    /** Suggests online players' names. */
    public static final SuggestionProvider<CommandSourceStack> ONLINE = (ctx, builder) ->
            SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerNames(), builder);

    /**
     * An online player: exact name, then exact nickname, then the only player whose name or
     * nickname starts with the input.
     */
    public static Optional<ServerPlayer> online(MinecraftServer server, String input) {
        ServerPlayer exact = server.getPlayerList().getPlayerByName(input);
        if (exact != null) return Optional.of(exact);
        String needle = Text.plain(input).toLowerCase(Locale.ROOT);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (needle.equals(Names.plainNick(p))) return Optional.of(p);
        }
        List<ServerPlayer> matches = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String nick = Names.plainNick(p);
            if (p.getGameProfile().getName().toLowerCase(Locale.ROOT).startsWith(needle)
                    || (nick != null && nick.startsWith(needle))) {
                matches.add(p);
            }
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** Any player who has joined before: online match first, then the server's name cache. */
    public static Optional<Target> any(MinecraftServer server, String input) {
        Optional<ServerPlayer> on = online(server, input);
        if (on.isPresent()) {
            ServerPlayer p = on.get();
            return Optional.of(new Target(p.getUUID(), p.getGameProfile().getName(), p));
        }
        // Our own index of players who've joined: the vanilla profile cache would ask Mojang
        // (blocking the server) or, in offline mode, invent a profile for any name.
        return UserStore.byName(input).map(k -> new Target(k.uuid(), k.name(), server.getPlayerList().getPlayer(k.uuid())));
    }

    private PlayerLookup() {}
}
