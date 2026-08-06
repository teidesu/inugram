package org.telegram.messenger;

public class LocaleController {
    /** the harness has no resources, so a string is its own id - enough to assert which one it is */
    public static String getString(int res) {
        return "string:" + res;
    }

    public static String formatString(int res, Object... args) {
        StringBuilder out = new StringBuilder("string:" + res);
        for (Object arg : args) out.append('|').append(arg);
        return out.toString();
    }
}
