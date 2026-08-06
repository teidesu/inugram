package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;

/**
 * the ui-thread hop. The harness has one thread and one ordering, so this runs inline - which is
 * the strictest version of what the app gives: an observer is registered before the loader could
 * possibly have reported anything.
 *
 * {@link #inu_deferUi} buys back the window the app has and this does not: two posts made in order
 * run in that order, but a caller can act between them. Off by default, so nothing that does not
 * ask for it sees a different queue.
 */
public class AndroidUtilities {
    private static final List<Runnable> deferred = new ArrayList<>();
    private static boolean defer = false;

    public static void runOnUIThread(Runnable runnable) {
        if (defer) {
            deferred.add(runnable);
            return;
        }
        runnable.run();
    }

    public static void inu_deferUi(boolean value) {
        defer = value;
    }

    /** runs what was held, in the order it was posted, including anything those posts add */
    public static void inu_runUi() {
        while (!deferred.isEmpty()) {
            List<Runnable> pending = new ArrayList<>(deferred);
            deferred.clear();
            for (Runnable runnable : pending) runnable.run();
        }
    }

    public static void inu_reset() {
        deferred.clear();
        defer = false;
    }
}
