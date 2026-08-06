package dalvik.system;

import java.util.ArrayList;
import java.util.List;

/**
 * stands in for android's dex loader, which has no jvm equivalent: a plain jvm cannot define a class
 * out of a dex file at all.
 *
 * It records what it was asked to load and otherwise behaves as its parent, which is enough for the
 * decisions the bridge makes around it (whether a file is staged, whether it is bounded, whether the
 * loader is consulted before the app's own). Nothing here can tell whether the bytes were a real
 * dex; on device the platform's verifier owns that, and it reports by failing to define the class.
 */
public class DexClassLoader extends ClassLoader {
    public static final List<String> loaded = new ArrayList<>();

    public DexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(parent);
        loaded.add(dexPath);
    }
}
