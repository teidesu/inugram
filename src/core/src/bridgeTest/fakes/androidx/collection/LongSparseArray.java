package androidx.collection;

import java.util.LinkedHashMap;
import java.util.Map;

/** the parts of it the bridge touches: a long-keyed map, ordered so a walk is reproducible */
public class LongSparseArray<E> {
    private final Map<Long, E> entries = new LinkedHashMap<>();

    public E get(long key) {
        return entries.get(key);
    }

    public void put(long key, E value) {
        entries.put(key, value);
    }

    public void remove(long key) {
        entries.remove(key);
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
