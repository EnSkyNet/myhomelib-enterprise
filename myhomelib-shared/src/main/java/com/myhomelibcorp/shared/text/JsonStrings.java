package com.myhomelibcorp.shared.text;

import java.util.Locale;

/** Minimal JSON string-content escaping shared by bounded renderers/exporters. */
public final class JsonStrings {
    private JsonStrings() { }

    /** Escapes JSON string content without adding surrounding quotes. Null maps to an empty string. */
    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** Returns a complete quoted JSON string value. */
    public static String quote(String value) {
        return '"' + escape(value) + '"';
    }
}
