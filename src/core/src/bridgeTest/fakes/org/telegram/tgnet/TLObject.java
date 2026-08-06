package org.telegram.tgnet;

public class TLObject {
    public int networkType;
    public boolean disableFree = false;

    /** how many times stock would have handed this object's buffers back to the pool */
    public int inu_freeCount;

    public TLObject() {}

    public void freeResources() {
        if (disableFree) return;
        inu_freeCount++;
    }
}
