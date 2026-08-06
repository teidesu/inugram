package org.telegram.messenger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * the event bus stock reports every transfer on. Only the file events exist here, with stock's own
 * ids being positional rather than meaningful - what matters is that they are distinct.
 */
public class NotificationCenter {
    public interface NotificationCenterDelegate {
        void didReceivedNotification(int id, int account, Object... args);
    }

    public static final int fileUploaded = 1;
    public static final int fileUploadFailed = 2;
    public static final int fileUploadProgressChanged = 3;
    public static final int fileLoadProgressChanged = 4;
    public static final int fileLoaded = 5;
    public static final int fileLoadFailed = 6;

    public static final int dialogsNeedReload = 7;
    public static final int updateInterfaces = 8;

    private static final NotificationCenter[] instances = new NotificationCenter[8];
    private static NotificationCenter globalInstance;

    private final Map<Integer, List<NotificationCenterDelegate>> observers = new HashMap<>();
    private int currentAccount;

    public static NotificationCenter getInstance(int num) {
        if (instances[num] == null) {
            instances[num] = new NotificationCenter();
            instances[num].currentAccount = num;
        }
        return instances[num];
    }

    /** the app-wide centre, which stock numbers -1 so an observer can tell it from an account's */
    public static NotificationCenter getGlobalInstance() {
        if (globalInstance == null) {
            globalInstance = new NotificationCenter();
            globalInstance.currentAccount = -1;
        }
        return globalInstance;
    }

    public static void inu_reset() {
        for (int i = 0; i < instances.length; i++) instances[i] = null;
        globalInstance = null;
    }

    public void addObserver(NotificationCenterDelegate observer, int id) {
        observers.computeIfAbsent(id, key -> new ArrayList<>()).add(observer);
    }

    public void removeObserver(NotificationCenterDelegate observer, int id) {
        List<NotificationCenterDelegate> list = observers.get(id);
        if (list != null) list.remove(observer);
    }

    public void postNotificationName(int id, Object... args) {
        List<NotificationCenterDelegate> list = observers.get(id);
        if (list == null) return;
        for (NotificationCenterDelegate observer : new ArrayList<>(list)) {
            observer.didReceivedNotification(id, currentAccount, args);
        }
    }

    /** how many observers are still registered, i.e. whether a finished transfer let go */
    public int inu_observerCount() {
        int total = 0;
        for (List<NotificationCenterDelegate> list : observers.values()) total += list.size();
        return total;
    }
}
