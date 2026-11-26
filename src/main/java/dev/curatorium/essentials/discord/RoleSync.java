package dev.curatorium.essentials.discord;

import dev.curatorium.essentials.perm.LuckPermsGroups;
import dev.curatorium.essentials.perm.Perms;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;

/**
 * Discord roles to LuckPerms groups ({@code rolesToGroups}) and LuckPerms groups to Discord roles
 * ({@code groupsToRoles}) for linked players, plus nickname sync. Everything is asynchronous:
 * Discord and LuckPerms both answer on their own threads.
 */
final class RoleSync {
    /** Problems already logged, so a missing permission isn't logged on every sync. */
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();
    private static boolean subscribed;

    static Guild guild() {
        JDA jda = Discord.jda();
        long id = DiscordConfig.parseId(DiscordConfig.GUILD.get());
        return jda == null || id == 0 ? null : jda.getGuildById(id);
    }

    private static boolean groupsAvailable() {
        return Perms.luckPermsActive();
    }

    /** Listens for LuckPerms changes once, so group changes reach Discord straight away. */
    static synchronized void subscribe() {
        if (subscribed || !groupsAvailable()) return;
        subscribed = true;
        LuckPermsGroups.onUserChange(uuid -> {
            Links.Link link = Links.get(uuid);
            if (link != null && Discord.running()) toDiscord(uuid, link.discordId(), true);
        });
    }

    /** After linking: adds what's missing on both sides, removing nothing. */
    static void onLinked(UUID player, long discordId, String name) {
        withMember(discordId, m -> {
            fromDiscord(player, m, false);
            toDiscord(player, discordId, false);
            nickname(m, name);
        });
    }

    /** On join and on the timer: Discord's roles decide the groups first, then the groups decide the roles. */
    static void full(UUID player, long discordId, String name) {
        withMember(discordId, m -> {
            fromDiscord(player, m, true);
            toDiscord(player, discordId, true);
            nickname(m, name);
        });
    }

    /** A linked member's roles changed (needs the member intent), or they left the server ({@code m} null). */
    static void onRolesChanged(UUID player, Member m) {
        if (m == null) {
            if (groupsAvailable()) {
                Set<String> remove = new HashSet<>(DiscordConfig.rolesToGroups().values());
                LuckPermsGroups.apply(player, Set.of(), remove);
            }
            return;
        }
        fromDiscord(player, m, true);
    }

    /** After unlinking: takes away the synced groups and roles, if removeOnUnlink is on. */
    static void onUnlinked(UUID player, long discordId) {
        if (!DiscordConfig.REMOVE_ON_UNLINK.get()) return;
        if (groupsAvailable()) LuckPermsGroups.apply(player, Set.of(), new HashSet<>(DiscordConfig.rolesToGroups().values()));
        Guild g = guild();
        if (g == null) return;
        for (long roleId : DiscordConfig.groupsToRoles().values()) {
            Role r = g.getRoleById(roleId);
            if (r != null) g.removeRoleFromMember(UserSnowflake.fromId(discordId), r).queue(null, err -> warn("remove-" + roleId, err));
        }
    }

    // ------------------------------------------------------------------ the two directions

    private static void fromDiscord(UUID player, Member m, boolean remove) {
        Map<Long, String> map = DiscordConfig.rolesToGroups();
        if (map.isEmpty() || !groupsAvailable()) return;
        Set<Long> has = new HashSet<>();
        for (Role r : m.getRoles()) has.add(r.getIdLong());
        Set<String> add = new HashSet<>(), take = new HashSet<>();
        map.forEach((role, group) -> {
            if (!LuckPermsGroups.exists(group)) {
                warnOnce("group-" + group, "Discord role sync: there's no LuckPerms group called " + group);
                return;
            }
            if (has.contains(role)) add.add(group);
            else if (remove) take.add(group);
        });
        LuckPermsGroups.apply(player, add, take).exceptionally(err -> {
            Discord.LOG.warn("Discord role sync: could not change {}'s groups: {}", player, err.toString());
            return null;
        });
    }

    private static void toDiscord(UUID player, long discordId, boolean remove) {
        Map<String, Long> map = DiscordConfig.groupsToRoles();
        Guild g = guild();
        if (map.isEmpty() || !groupsAvailable() || g == null) return;
        LuckPermsGroups.groups(player).thenAccept(groups -> withMember(discordId, m -> {
            Set<Long> has = new HashSet<>();
            for (Role r : m.getRoles()) has.add(r.getIdLong());
            map.forEach((group, roleId) -> {
                Role role = g.getRoleById(roleId);
                if (role == null) {
                    warnOnce("role-" + roleId, "Discord role sync: there's no role " + Long.toUnsignedString(roleId) + " in " + g.getName());
                    return;
                }
                if (!g.getSelfMember().canInteract(role)) {
                    warnOnce("high-" + roleId, "Discord role sync: the bot can't give @" + role.getName()
                            + "; move the bot's role above it and give it Manage Roles");
                    return;
                }
                if (groups.contains(group) && !has.contains(roleId)) {
                    g.addRoleToMember(m, role).queue(null, err -> warn("add-" + roleId, err));
                } else if (remove && !groups.contains(group) && has.contains(roleId)) {
                    g.removeRoleFromMember(m, role).queue(null, err -> warn("remove-" + roleId, err));
                }
            });
        }));
    }

    private static void nickname(Member m, String name) {
        if (!DiscordConfig.SYNC_NICKNAMES.get() || name == null || name.equals(m.getNickname())) return;
        if (m.isOwner() || !m.getGuild().getSelfMember().canInteract(m)) {
            warnOnce("nick-" + m.getId(), "Discord nickname sync: can't rename " + m.getUser().getName()
                    + " (the server owner, or above the bot's role)");
            return;
        }
        m.modifyNickname(name).queue(null, err -> warn("nick", err));
    }

    // ------------------------------------------------------------------ helpers

    private static void withMember(long discordId, java.util.function.Consumer<Member> then) {
        Guild g = guild();
        if (g == null) return;
        g.retrieveMemberById(discordId).queue(then, err -> {
            if (err instanceof ErrorResponseException e && e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER) return; // not in the server
            warn("member", err);
        });
    }

    private static void warn(String key, Throwable err) {
        warnOnce(key + err.getMessage(), "Discord role sync failed: " + err.getMessage());
    }

    private static void warnOnce(String key, String message) {
        if (warned.add(key)) Discord.LOG.warn(message);
    }

    private RoleSync() {}
}
