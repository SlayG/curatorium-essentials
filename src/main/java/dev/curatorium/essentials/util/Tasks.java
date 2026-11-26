package dev.curatorium.essentials.util;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Runs things a number of ticks later, on the server thread. */
public final class Tasks {
    private record Task(long due, Runnable run) {}

    private static final List<Task> tasks = new ArrayList<>();
    private static long tick;

    public static void registerEvents(IEventBus bus) {
        bus.addListener(ServerTickEvent.Post.class, e -> {
            tick++;
            if (tasks.isEmpty()) return;
            List<Task> due = new ArrayList<>();
            tasks.removeIf(t -> {
                if (t.due() > tick) return false;
                due.add(t);
                return true;
            });
            for (Task t : due) {
                try {
                    t.run().run();
                } catch (RuntimeException ex) {
                    dev.curatorium.essentials.CuratoriumEssentials.LOGGER.error("Delayed task failed", ex);
                }
            }
        });
        bus.addListener(ServerStoppedEvent.class, e -> tasks.clear());
    }

    /** Runs {@code run} after {@code ticks} ticks (at least 1). Server thread only. */
    public static void later(int ticks, Runnable run) {
        tasks.add(new Task(tick + Math.max(1, ticks), run));
    }

    private Tasks() {}
}
