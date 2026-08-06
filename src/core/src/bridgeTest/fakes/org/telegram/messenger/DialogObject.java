package org.telegram.messenger;

public class DialogObject {
    /** stock's own rule (DialogObject.java): the 0x4000... bit set and the sign bit clear */
    public static boolean isEncryptedDialog(long dialogId) {
        return (dialogId & 0x4000000000000000L) != 0 && (dialogId & 0x8000000000000000L) == 0;
    }
}
