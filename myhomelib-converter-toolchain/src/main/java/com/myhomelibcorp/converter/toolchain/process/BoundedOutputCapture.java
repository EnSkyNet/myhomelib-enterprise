package com.myhomelibcorp.converter.toolchain.process;

/** Shared defaults for bounded native-process diagnostics. */
public final class BoundedOutputCapture {
    public static final int DEFAULT_MAX_BYTES = 256 * 1024;
    public static final int USER_VISIBLE_DIAGNOSTIC_LIMIT = 2048;

    private BoundedOutputCapture() { }

    public static String compact(String text) {
        if (text == null || text.isBlank()) return "";
        String compact = text.strip().replaceAll("[\\r\\n]+", " | ");
        if (compact.length() > USER_VISIBLE_DIAGNOSTIC_LIMIT) {
            return compact.substring(0, USER_VISIBLE_DIAGNOSTIC_LIMIT) + "…";
        }
        return compact;
    }
}
