package org.telegram.messenger;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

/**
 * single-threaded stand-in for stock's handler-backed queue. every queue in the harness shares one
 * ordering (due time, then post order) and one virtual clock, which is the ordering stock's own
 * `Handler` gives a single queue and the only one the bridge is allowed to depend on across two -
 * notably "a due expiry timer sorts ahead of a runnable posted now".
 */
public class DispatchQueue {
    static class Task implements Comparable<Task> {
        final Runnable runnable;
        final long due;
        final long seq;
        final DispatchQueue queue;

        Task(Runnable runnable, long due, long seq, DispatchQueue queue) {
            this.runnable = runnable;
            this.due = due;
            this.seq = seq;
            this.queue = queue;
        }

        @Override
        public int compareTo(Task other) {
            if (due != other.due) return Long.compare(due, other.due);
            return Long.compare(seq, other.seq);
        }
    }

    static final List<Task> pending = new ArrayList<>();
    private static long nextSeq = 1;

    public final String name;

    public DispatchQueue(String name) {
        this.name = name;
    }

    public boolean postRunnable(Runnable runnable) {
        return postRunnable(runnable, 0);
    }

    public boolean postRunnable(Runnable runnable, long delay) {
        pending.add(new Task(runnable, SystemClock.uptimeMillis() + delay, nextSeq++, this));
        return true;
    }

    public void cancelRunnable(Runnable runnable) {
        pending.removeIf(task -> task.runnable == runnable);
    }
}
