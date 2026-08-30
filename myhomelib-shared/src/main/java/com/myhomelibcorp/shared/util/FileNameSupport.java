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
}
