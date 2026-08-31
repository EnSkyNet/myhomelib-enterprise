package com.myhomelibcorp.application.catalog;

/**
 * MyHomeLib-compatible storage location for online FB2 books.
 * Upstream does not use the catalog package name (online.zip/extra.zip) as the book archive.
 * It generates: <first-letter>/<author>/<libId> <title>.fb2.zip.
 */
public final class LegacyOnlineBookLocation {
    private LegacyOnlineBookLocation() { }

    public static String archivePath(String firstAuthorFullName, String title, String libId, String fileName) {
        String author = cleanComponent(firstAuthorFullName);
        if (author.isBlank()) author = "Невідомий Автор";
        char first = author.charAt(0);
        String letter = Character.isLetterOrDigit(first) ? String.valueOf(first) : "_";

        String safeTitle = cleanComponent(title);
        if (safeTitle.isBlank()) safeTitle = "Без назви";
        String id = cleanComponent(libId);
        if (id.isBlank()) id = stem(fileName);
        if (id.isBlank()) id = "book";

        return letter + "/" + author + "/" + id + " " + safeTitle + ".fb2.zip";
    }

    private static String stem(String value) {
        String v = value == null ? "" : value.trim();
        int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
        if (slash >= 0) v = v.substring(slash + 1);
        int dot = v.lastIndexOf('.');
        return dot > 0 ? v.substring(0, dot) : v;
    }

    /** Mirrors upstream CheckSymbols: filesystem-denied/control characters become spaces. */
    private static String cleanComponent(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 32 || ch == '<' || ch == '>' || ch == ':' || ch == '"' || ch == '/' || ch == '\\'
                    || ch == '|' || ch == '?' || ch == '*') out.append(' ');
            else out.append(ch);
        }
        String cleaned = out.toString().replaceAll("\\s+", " ").trim();
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        return cleaned;
    }
}
