package dev.curatorium.essentials.teleport;

import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.data.Location;
import dev.curatorium.essentials.data.UserData;
import dev.curatorium.essentials.data.UserStore;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Durations;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Every teleport the mod does goes through here: the siege check, safe spots, {@code /back},
 * and for the "travel" teleports (homes, warps, spawn, back, requests, random) the warmup and
 * cooldown.
 */
public final class Teleports {
    private static final String COOLDOWN_KEY = "teleport";

    /** A delayed teleport waiting for its warmup. */
    private static final class Pending {
        final Location from;
        final Location to;
        final Component done;
        int ticks;

        Pending(Location from, Location to, Component done, int ticks) {
            this.from = from;
            this.to = to;
            this.done = done;
            this.ticks = ticks;
        }
    }

    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public static void registerEvents(IEventBus bus) {
        bus.addListener(ServerTickEvent.Post.class, e -> {
            if (pending.isEmpty()) return;
            for (var entry : Map.copyOf(pending).entrySet()) {
                ServerPlayer p = e.getServer().getPlayerList().getPlayer(entry.getKey());
                Pending t = entry.getValue();
                if (p == null) {
                    pending.remove(entry.getKey());
                    continue;
                }
                if (EssConfig.TP_CANCEL_MOVE.get() && (!t.from.sameDimension(p) || t.from.distanceSqr(p) > 0.5 * 0.5)) {
                    pending.remove(entry.getKey());
                    p.sendSystemMessage(Messages.get("teleport.cancelledMove"));
                    continue;
                }
                if (--t.ticks <= 0) {
                    pending.remove(entry.getKey());
                    now(p, t.to, true, t.done);
                }
            }
        });
        bus.addListener(LivingDamageEvent.Post.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p && EssConfig.TP_CANCEL_DAMAGE.get() && pending.remove(p.getUUID()) != null) {
                p.sendSystemMessage(Messages.get("teleport.cancelledDamage"));
            }
        });
        bus.addListener(EventPriority.LOWEST, LivingDeathEvent.class, e -> {
            if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p)) return;
            pending.remove(p.getUUID());
            if (EssConfig.BACK_ON_DEATH.get() && Perms.has(p, "essentials.back.ondeath", Perms.OP)) {
                UserData d = UserStore.get(p);
                d.lastLocation = Location.of(p);
                d.markDirty();
            }
        });
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> pending.remove(e.getEntity().getUUID()));
    }

    /** Whether a spot is in a claim under siege (only matters with Curatorium Claims installed). */
    static boolean sieged(ServerLevel level, double x, double y, double z) {
        return ClaimsHook.underSiege(level, BlockPos.containing(x, y, z));
    }

    /** Why the last {@link #go} call refused, for telling whoever moved someone else. Server thread only. */
    private static Component lastRefusal;

    private static boolean refuse(ServerPlayer p, Component why) {
        lastRefusal = why;
        p.sendSystemMessage(why);
        return false;
    }

    /** "Couldn't teleport X", with the reason when there was one. */
    public static Component failedFor(ServerPlayer p) {
        Component why = lastRefusal;
        return why == null ? Messages.get("teleport.failedFor", p.getDisplayName())
                : Messages.get("teleport.failedForReason", p.getDisplayName(), why);
    }

    /**
     * Teleports a player. Tells them (and returns false) if it can't.
     *
     * @param travel  a travel teleport: warmup and cooldown apply
     * @param done    told to the player once they've arrived (null for nothing)
     */
    public static boolean go(ServerPlayer p, Location dest, boolean travel, Component done) {
        lastRefusal = null;
        ServerLevel level = dest.level(p.server);
        if (level == null) return refuse(p, Messages.get("teleport.noWorld", dest.dimension()));
        if (!Perms.has(p, "essentials.teleport.bypass-siege", Perms.NOBODY)
                && (sieged(p.serverLevel(), p.getX(), p.getY(), p.getZ()) || sieged(level, dest.x(), dest.y(), dest.z()))) {
            return refuse(p, Messages.get("teleport.siege"));
        }
        UserData d = UserStore.get(p);
        long now = System.currentTimeMillis();
        if (travel && !Perms.has(p, "essentials.teleport.cooldown.bypass", Perms.ADMIN)) {
            Long until = d.cooldowns.get(COOLDOWN_KEY);
            if (until != null && until > now) return refuse(p, Messages.get("teleport.cooldown", Durations.format(until - now)));
        }
        int warmup = EssConfig.TP_WARMUP.get();
        if (travel && warmup > 0 && !Perms.has(p, "essentials.teleport.timer.bypass", Perms.ADMIN)) {
            pending.put(p.getUUID(), new Pending(Location.of(p), dest, done, warmup * 20));
            p.sendSystemMessage(Messages.get(EssConfig.TP_CANCEL_MOVE.get() ? "teleport.warmup" : "teleport.warmupNoMove", warmup));
            return true;
        }
        return now(p, dest, travel, done);
    }

    private static boolean now(ServerPlayer p, Location dest, boolean travel, Component done) {
        ServerLevel level = dest.level(p.server);
        if (level == null) return false;
        Location target = dest;
        boolean skipSafety = p.isCreative() || p.isSpectator() || p.getAbilities().flying
                || Perms.has(p, "essentials.teleport.safety.bypass", Perms.NOBODY);
        if (EssConfig.TP_SAFE.get() && !skipSafety) {
            target = Safety.find(level, dest);
            if (target == null) {
                p.sendSystemMessage(Messages.get("teleport.unsafe"));
                return false;
            }
        }
        UserData d = UserStore.get(p);
        d.lastLocation = Location.of(p);
        if (travel && EssConfig.TP_COOLDOWN.get() > 0) d.cooldowns.put(COOLDOWN_KEY, System.currentTimeMillis() + EssConfig.TP_COOLDOWN.get() * 1000L);
        d.markDirty();
        target.teleport(p);
        p.fallDistance = 0;
        if (done != null) p.sendSystemMessage(done);
        return true;
    }

    /** Cancels a waiting teleport, if any. */
    public static void cancel(ServerPlayer p) {
        pending.remove(p.getUUID());
    }

    /** Whether others may teleport to (or pull) this player: their /tptoggle, unless the source may override it. */
    public static boolean accepts(ServerPlayer target, ServerPlayer by) {
        return by == target || UserStore.get(target).teleportEnabled || (by != null && Perms.has(by, "essentials.tptoggle.override", Perms.ADMIN));
    }

    private Teleports() {}
}
