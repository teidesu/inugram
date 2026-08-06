package android.os;

/**
 * virtual clock. the harness drives it from the same scheduler that owns the queues, so a chain's
 * 10s budget is reached by advancing time rather than by waiting for it.
 */
public final class SystemClock {
    private static long uptime = 1_000L;

    private SystemClock() {}

    public static long uptimeMillis() {
        return uptime;
    }

    public static void inu_setUptimeMillis(long value) {
        uptime = value;
    }
}
