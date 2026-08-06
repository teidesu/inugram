package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

/** the app's wrapper around a TL message; the bridge reads its id and the message behind it */
public class MessageObject {
    public TLRPC.Message messageOwner;

    public MessageObject(TLRPC.Message messageOwner) {
        this.messageOwner = messageOwner;
    }

    public int getId() {
        return messageOwner.id;
    }
}
