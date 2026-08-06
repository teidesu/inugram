package android.util;

import java.util.ArrayList;
import java.util.List;

public final class Log {
    public static final List<String> lines = new ArrayList<>();

    private Log() {}

    public static int d(String tag, String msg) { return record("D", tag, msg); }
    public static int i(String tag, String msg) { return record("I", tag, msg); }
    public static int w(String tag, String msg) { return record("W", tag, msg); }
    public static int e(String tag, String msg) { return record("E", tag, msg); }
    public static int e(String tag, String msg, Throwable t) { return record("E", tag, msg); }

    private static int record(String level, String tag, String msg) {
        lines.add(level + "/" + tag + ": " + msg);
        return 0;
    }
}
