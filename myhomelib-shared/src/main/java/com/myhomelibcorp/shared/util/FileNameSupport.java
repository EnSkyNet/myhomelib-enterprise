package com.myhomelibcorp.shared.util;

import java.util.Locale;

/** Path-string helpers that work for both '/' and '\\' separators. */
public final class FileNameSupport {
    private FileNameSupport() { }

    public static String extension(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Filesystem-independent basename for logical archive/resource names. */
    public static String baseName(String name) {
        if (name == null || name.isBlank()) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
