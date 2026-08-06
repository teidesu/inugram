package org.telegram.messenger;

import androidx.collection.LongSparseArray;
import org.telegram.tgnet.TLRPC;

/** the draft store `getDraft` reads: dialog id -> topic id -> the draft the input field would show */
public class MediaDataController {
    private static final MediaDataController[] instances = new MediaDataController[8];

    public final LongSparseArray<LongSparseArray<TLRPC.DraftMessage>> drafts = new LongSparseArray<>();

    public static MediaDataController getInstance(int num) {
        if (instances[num] == null) instances[num] = new MediaDataController();
        return instances[num];
    }

    public static void inu_reset() {
        for (int i = 0; i < instances.length; i++) instances[i] = null;
    }

    public TLRPC.DraftMessage getDraft(long dialogId, long threadId) {
        LongSparseArray<TLRPC.DraftMessage> threads = drafts.get(dialogId);
        if (threads == null) return null;
        return threads.get(threadId);
    }

    public void inu_putDraft(long dialogId, long threadId, TLRPC.DraftMessage draft) {
        LongSparseArray<TLRPC.DraftMessage> threads = drafts.get(dialogId);
        if (threads == null) {
            threads = new LongSparseArray<>();
            drafts.put(dialogId, threads);
        }
        threads.put(threadId, draft);
    }
}
