package dev.curatorium.essentials.perm;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;

/**
 * LuckPerms groups for Discord role sync. Call only when {@link Perms#luckPermsActive()}: this
 * class refers to the LuckPerms API directly.
 */
public final class LuckPermsGroups {
    private static LuckPerms api() {
        return LuckPermsProvider.get();
    }

    /** Every group the player is in, directly or through another group (lower case). Loads offline players. */
    public static CompletableFuture<Set<String>> groups(UUID player) {
        return api().getUserManager().loadUser(player).thenApply(u -> u.getInheritedGroups(QueryOptions.nonContextual())
                .stream().map(Group::getName).collect(Collectors.toSet()));
    }

    /** Adds and removes direct group memberships. Groups that don't exist are skipped. */
    public static CompletableFuture<Void> apply(UUID player, Set<String> add, Set<String> remove) {
        if (add.isEmpty() && remove.isEmpty()) return CompletableFuture.completedFuture(null);
        LuckPerms lp = api();
        return lp.getUserManager().loadUser(player).thenCompose(u -> {
            Set<String> direct = u.getNodes(NodeType.INHERITANCE).stream().map(InheritanceNode::getGroupName).collect(Collectors.toSet());
            boolean changed = false;
            for (String g : add) {
                if (direct.contains(g) || lp.getGroupManager().getGroup(g) == null) continue;
                u.data().add(InheritanceNode.builder(g).build());
                changed = true;
            }
            for (String g : remove) {
                if (!direct.contains(g) || add.contains(g)) continue;
                u.data().remove(InheritanceNode.builder(g).build());
                changed = true;
            }
            return changed ? lp.getUserManager().saveUser(u) : CompletableFuture.<Void>completedFuture(null);
        });
    }

    /** Whether a group by that name exists. */
    public static boolean exists(String group) {
        return api().getGroupManager().getGroup(group) != null;
    }

    /** Calls {@code listener} with a player's UUID whenever their permissions or groups change. */
    public static void onUserChange(Consumer<UUID> listener) {
        api().getEventBus().subscribe(NodeMutateEvent.class, e -> {
            if (e.isUser() && e.getTarget() instanceof User u) listener.accept(u.getUniqueId());
        });
    }

    /** A loaded player's permission without needing them online (for checks at login). Null if unknown. */
    public static Boolean check(UUID player, String node) {
        User u = api().getUserManager().getUser(player);
        if (u == null) return null;
        Tristate t = u.getCachedData().getPermissionData(QueryOptions.nonContextual()).checkPermission(node);
        return t == Tristate.UNDEFINED ? null : t.asBoolean();
    }

    private LuckPermsGroups() {}
}
