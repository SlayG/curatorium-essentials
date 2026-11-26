package dev.curatorium.essentials.player;

import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.text.Text;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Nicknames and display names. A nickname replaces the player's name everywhere vanilla uses
 * their display name (chat, join/leave and death messages, {@code /msg}) and, if
 * {@code names.tabListDisplayName} is on, in the tab list. The name above their head can't be
 * changed without a client mod, so it stays their real name.
 */
public final class Names {
    public static void registerEvents(IEventBus bus) {
        bus.addListener(PlayerEvent.NameFormat.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                String nick = UserStore.get(p).nick;
                if (nick != null) e.setDisplayname(Text.format(EssConfig.NICK_PREFIX.get() + nick));
            }
        });
        bus.addListener(PlayerEvent.TabListNameFormat.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p) || !EssConfig.TAB_DISPLAY_NAME.get()) return;
            MutableComponent name = Component.empty();
            Perms.Meta meta = EssConfig.TAB_PREFIX.get() ? Perms.meta(p) : Perms.Meta.NONE;
            if (!meta.prefix().isEmpty()) name.append(Text.format(meta.prefix()));
            name.append(p.getDisplayName());
            if (!meta.suffix().isEmpty()) name.append(Text.format(meta.suffix()));
            if (Afk.isAfk(p)) name.append(Messages.get("afk.tabSuffix"));
            e.setDisplayName(name);
        });
    }

    /** Updates the player's display name and tab-list entry after a nickname or AFK change. */
    public static void refresh(ServerPlayer player) {
        player.refreshDisplayName();
        player.refreshTabListName();
    }

    /** The display name as formatted text: nickname prefix + nickname, or the real name. */
    public static String rawDisplayName(ServerPlayer player) {
        String nick = UserStore.get(player).nick;
        return nick == null ? player.getGameProfile().getName() : EssConfig.NICK_PREFIX.get() + nick;
    }

    /** The nickname without codes, lower case, or null without one. */
    public static String plainNick(ServerPlayer player) {
        String nick = UserStore.get(player).nick;
        return nick == null ? null : Text.plain(nick).toLowerCase(Locale.ROOT);
    }

    private Names() {}
}
