package com.myhomelibcorp.domain.model.valueobject;

import lombok.Value;

@Value
public class BookFile {
    String fileName;
    String folder;
    String archiveEntry;
    long fileSize;
    String collectionRoot;

    public static BookFile empty() {
        return new BookFile("", "", "", 0, "");
    }

    /**
     * Resolves the physical book path without ever dropping {@code fileName}.
     * Windows drive/UNC paths are recognized even when the application is tested on a non-Windows host.
     */
    public String getFullPath() {
        String name = safe(fileName);
        if (name.isBlank()) return resolveBase();
        if (isAbsoluteLike(name)) return name;
        String base = resolveBase();
        return base.isBlank() ? name : join(base, name);
    }

    private String resolveBase() {
        String dir = safe(folder);
        String root = safe(collectionRoot);
        if (!dir.isBlank() && isAbsoluteLike(dir)) return dir;
        if (!root.isBlank()) return dir.isBlank() ? root : join(root, dir);
        return dir;
    }

    private static String join(String base, String child) {
        if (base == null || base.isBlank()) return safe(child);
        String cleanChild = safe(child).replaceFirst("^[\\\\/]+", "");
        if (cleanChild.isBlank()) return base;
        if (isWindowsLike(base)) {
            String cleanBase = base.replaceAll("[\\\\/]+$", "");
            return cleanBase + "\\" + cleanChild.replace('/', '\\');
        }
        String cleanBase = base.replaceAll("[\\/]+$", "");
        return cleanBase + "/" + cleanChild.replace('\\', '/');
    }

    private static boolean isAbsoluteLike(String value) {
        if (value == null || value.isBlank()) return false;
        return value.startsWith("/") || value.startsWith("\\\\") || value.startsWith("//")
                || value.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static boolean isWindowsLike(String value) {
        return value != null && (value.startsWith("\\\\") || value.matches("^[A-Za-z]:[\\\\/].*")
                || (value.indexOf('\\') >= 0 && value.indexOf('/') < 0));
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    public boolean hasArchiveEntry() {
        return archiveEntry != null && !archiveEntry.isBlank();
    }

    public String getDisplayName() {
        return fileName != null && !fileName.isBlank() ? fileName : "unknown.fb2";
    }

    public String getArchiveEntryName() {
        return hasArchiveEntry() ? archiveEntry : fileName;
    }
}
