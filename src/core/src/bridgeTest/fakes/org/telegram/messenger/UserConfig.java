package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

public class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 8;

    public static int selectedAccount;

    private static final UserConfig[] instances = new UserConfig[MAX_ACCOUNT_COUNT];

    public final int currentAccount;
    public long clientUserId;
    /** null == this slot is not logged in, which `isValidAccount` is stock's own name for */
    public TLRPC.User currentUser;

    private UserConfig(int account) {
        this.currentAccount = account;
        this.clientUserId = 100L + account;
    }

    public static UserConfig getInstance(int num) {
        if (instances[num] == null) instances[num] = new UserConfig(num);
        return instances[num];
    }

    public static boolean isValidAccount(int num) {
        return num >= 0 && num < MAX_ACCOUNT_COUNT && instances[num] != null && instances[num].currentUser != null;
    }

    public static void inu_reset() {
        for (int i = 0; i < instances.length; i++) instances[i] = null;
        selectedAccount = 0;
    }

    public long getClientUserId() {
        return clientUserId;
    }

    public TLRPC.User getCurrentUser() {
        return currentUser;
    }
}
