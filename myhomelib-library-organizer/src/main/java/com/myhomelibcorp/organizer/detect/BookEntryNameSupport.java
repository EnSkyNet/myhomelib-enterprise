package com.myhomelibcorp.organizer.detect;

import java.util.Locale;
import java.util.Set;

/**
 * Central allow-list for logical book payloads that may appear inside a multi-book ZIP.
 * Generic ZIP is intentionally excluded to avoid unbounded recursive archive expansion.
 */
public final class BookEntryNameSupport {
    private static final Set<String> EXTENSIONS = Set.of(
            "fb2", "epub", "mobi", "prc", "azw", "azw3", "azw4",
            "docx", "odt", "rtf", "txt", "md", "markdown", "html", "htm", "htmlz",
            "pdf", "chm", "djvu", "djv", "lit", "lrf", "pdb", "pml", "pmlz",
            "rb", "snb", "tcr", "txtz", "cbz", "cbc", "cbr", "cb7"
    );

    private BookEntryNameSupport() { }

    public static boolean isSupportedBookEntry(String name) {
        if (name == null || name.isBlank()) return false;
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String leaf = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = leaf.lastIndexOf('.');
        if (dot < 0 || dot == leaf.length() - 1) return false;
        return EXTENSIONS.contains(leaf.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
