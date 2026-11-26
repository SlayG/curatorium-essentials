package dev.curatorium.essentials.util;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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
 * Nicknames join in with Phase 1.
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

    /** An online player: exact name first, then the only name starting with the input. */
    public static Optional<ServerPlayer> online(MinecraftServer server, String input) {
        ServerPlayer exact = server.getPlayerList().getPlayerByName(input);
        if (exact != null) return Optional.of(exact);
        String prefix = input.toLowerCase(Locale.ROOT);
        List<ServerPlayer> matches = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile().getName().toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(p);
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
        if (server.getProfileCache() == null) return Optional.empty();
        Optional<GameProfile> profile = server.getProfileCache().get(input);
        return profile.map(gp -> new Target(gp.getId(), gp.getName(), server.getPlayerList().getPlayer(gp.getId())));
    }

    private PlayerLookup() {}
}
