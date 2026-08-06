package org.telegram.messenger;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** what stock hands its loader for a photo; here it is only the pair the bridge passes through */
public class ImageLocation {
    public TLRPC.PhotoSize photoSize;
    public TLObject parent;

    public static ImageLocation getForObject(TLRPC.PhotoSize photoSize, TLObject object) {
        if (photoSize == null) return null;
        ImageLocation location = new ImageLocation();
        location.photoSize = photoSize;
        location.parent = object;
        return location;
    }
}
