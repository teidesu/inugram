package org.telegram.messenger;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Utilities {
    public interface Callback<T> {
        void run(T arg);
    }

    public static java.security.SecureRandom random = new java.security.SecureRandom();

    public static volatile DispatchQueue stageQueue = new DispatchQueue("stageQueue");
    public static volatile DispatchQueue globalQueue = new DispatchQueue("globalQueue");
    public static volatile DispatchQueue cacheClearQueue = new DispatchQueue("cacheClearQueue");

    /** runs everything already due, including what those runnables post, without moving the clock */
    public static int inu_drain() {
        int ran = 0;
        while (true) {
            DispatchQueue.Task next = inu_nextDue(SystemClock.uptimeMillis());
            if (next == null) return ran;
            DispatchQueue.pending.remove(next);
            next.runnable.run();
            ran++;
        }
    }

    /**
     * drains, then jumps the clock to the earliest remaining deadline and drains again, until
     * either nothing is left or [untilUptime] is reached. this is how a test reaches a timeout.
     */
    public static void inu_advanceTo(long untilUptime) {
        while (true) {
            inu_drain();
            DispatchQueue.Task next = inu_earliest();
            if (next == null || next.due > untilUptime) {
                SystemClock.inu_setUptimeMillis(untilUptime);
                inu_drain();
                return;
            }
            SystemClock.inu_setUptimeMillis(Math.max(next.due, SystemClock.uptimeMillis()));
        }
    }

    public static void inu_advanceBy(long millis) {
        inu_advanceTo(SystemClock.uptimeMillis() + millis);
    }

    public static List<String> inu_pendingQueues() {
        List<String> out = new ArrayList<>();
        for (DispatchQueue.Task task : DispatchQueue.pending) out.add(task.queue.name);
        return out;
    }

    public static void inu_reset() {
        DispatchQueue.pending.clear();
        SystemClock.inu_setUptimeMillis(1_000L);
    }

    private static DispatchQueue.Task inu_nextDue(long now) {
        DispatchQueue.Task best = inu_earliest();
        return best != null && best.due <= now ? best : null;
    }

    private static DispatchQueue.Task inu_earliest() {
        return DispatchQueue.pending.isEmpty() ? null : Collections.min(DispatchQueue.pending);
    }
}
