package org.telegram.messenger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * recording double for stock's transfer engine. Nothing here transfers: a test posts the
 * NotificationCenter events the real loader would, which is how a download that fails, one that was
 * already on disk, and one that reports progress are each written.
 *
 * `inu_paths` is what `getPathToMessage` answers, so a test decides whether the file exists.
 */
public class FileLoader {
    public static final int PRIORITY_NORMAL = 1;

    public static final int MEDIA_DIR_IMAGE = 0;
    public static final int MEDIA_DIR_AUDIO = 1;
    public static final int MEDIA_DIR_VIDEO = 2;
    public static final int MEDIA_DIR_DOCUMENT = 3;
    public static final int MEDIA_DIR_CACHE = 4;
    public static final int MEDIA_DIR_FILES = 5;

    /** stock answers null for a kind the app has never downloaded anything of; so does this */
    public static java.util.Map<Integer, File> inu_mediaDirs = new java.util.HashMap<>();

    public static File checkDirectory(int type) {
        return inu_mediaDirs.get(type);
    }

    public static class Load {
        public final Object what;
        public final Object parent;

        Load(Object what, Object parent) {
            this.what = what;
            this.parent = parent;
        }
    }

    private static final FileLoader[] instances = new FileLoader[8];

    public final List<Load> loads = new ArrayList<>();
    public final List<String> uploads = new ArrayList<>();
    public final java.util.Map<Integer, File> inu_paths = new java.util.HashMap<>();

    public static FileLoader getInstance(int num) {
        if (instances[num] == null) instances[num] = new FileLoader();
        return instances[num];
    }

    public static void inu_reset() {
        for (int i = 0; i < instances.length; i++) instances[i] = null;
    }

    public static String getAttachFileName(TLObject attach) {
        return getAttachFileName(attach, null);
    }

    public static String getAttachFileName(TLObject attach, String ext) {
        if (attach instanceof TLRPC.Document) {
            return ((TLRPC.Document) attach).id + (ext == null ? ".bin" : "." + ext);
        }
        if (attach instanceof TLRPC.PhotoSize) {
            return ((TLRPC.PhotoSize) attach).type + "." + (ext == null ? "jpg" : ext);
        }
        return "";
    }

    public static TLRPC.PhotoSize getClosestPhotoSizeWithSize(ArrayList<TLRPC.PhotoSize> sizes, int side) {
        if (sizes == null || sizes.isEmpty()) return null;
        return sizes.get(sizes.size() - 1);
    }

    public void loadFile(TLRPC.Document document, Object parentObject, int priority, int cacheType) {
        loads.add(new Load(document, parentObject));
    }

    public void loadFile(ImageLocation imageLocation, Object parentObject, String ext, int priority, int cacheType) {
        loads.add(new Load(imageLocation, parentObject));
    }

    public File getPathToMessage(TLRPC.Message message) {
        return inu_paths.get(message.id);
    }

    /**
     * stock starts nothing when that path is already in `uploadOperationPaths`, and the operation
     * that is running reports for every caller - so a second call recording a second upload would
     * hide the case the bridge listens for the notification itself to survive. Nothing is ever taken
     * out of `uploads` because an instance lives for one test.
     */
    public void uploadFile(String location, boolean encrypted, boolean small, int type) {
        if (uploads.contains(location)) return;
        uploads.add(location);
    }
}
