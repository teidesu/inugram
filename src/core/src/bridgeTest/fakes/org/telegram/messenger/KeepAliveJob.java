package org.telegram.messenger;

public class KeepAliveJob {
    public static int inu_finishCount;

    public static void finishJob() {
        inu_finishCount++;
    }
}
