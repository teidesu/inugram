package android.util;

import java.util.LinkedHashMap;
import java.util.Map;

public class SparseArray<E> {
    private final LinkedHashMap<Integer, E> entries = new LinkedHashMap<>();

    public void put(int key, E value) {
        entries.put(key, value);
    }

    public E get(int key) {
        return entries.get(key);
    }

    public int size() {
        return entries.size();
    }

    public int keyAt(int index) {
        return entryAt(index).getKey();
    }

    public E valueAt(int index) {
        return entryAt(index).getValue();
    }

    private Map.Entry<Integer, E> entryAt(int index) {
        int i = 0;
        for (Map.Entry<Integer, E> entry : entries.entrySet()) {
            if (i++ == index) return entry;
        }
        throw new IndexOutOfBoundsException(Integer.toString(index));
    }
}
