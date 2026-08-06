package android.content;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class Context {
    public static final int MODE_PRIVATE = 0;

    private final Map<String, SharedPreferences> preferences = new HashMap<>();
    private final File cacheDir;
    private final File filesDir;

    /** one root, split the way android splits them: a cache area it may evict, and durable storage */
    public Context(File root) {
        this(new File(root, "cache"), new File(root, "files"));
    }

    public Context(File cacheDir, File filesDir) {
        this.cacheDir = cacheDir;
        this.filesDir = filesDir;
        cacheDir.mkdirs();
        filesDir.mkdirs();
    }

    public File getCacheDir() {
        return cacheDir;
    }

    public File getFilesDir() {
        return filesDir;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences existing = preferences.get(name);
        if (existing == null) {
            existing = new SharedPreferences();
            preferences.put(name, existing);
        }
        return existing;
    }

    public boolean deleteSharedPreferences(String name) {
        return preferences.remove(name) != null;
    }
}
