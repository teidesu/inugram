package android.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory stand-in for the platform's store. The one behaviour worth reproducing rather than
 * approximating is the editor's: android applies `clear()` before the puts of the same edit
 * whichever order they were called in, and `apply()` is a write nothing has to wait for.
 */
public class SharedPreferences {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public Map<String, ?> getAll() {
        return new LinkedHashMap<>(values);
    }

    public String getString(String key, String defValue) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public Editor edit() {
        return new Editor();
    }

    public class Editor {
        private final Map<String, Object> puts = new LinkedHashMap<>();
        private final List<String> removals = new ArrayList<>();
        private boolean cleared;

        public Editor putString(String key, String value) {
            puts.put(key, value);
            return this;
        }

        public Editor remove(String key) {
            removals.add(key);
            return this;
        }

        public Editor clear() {
            cleared = true;
            return this;
        }

        public void apply() {
            commit();
        }

        public boolean commit() {
            if (cleared) values.clear();
            for (String key : removals) values.remove(key);
            values.putAll(puts);
            return true;
        }
    }
}
