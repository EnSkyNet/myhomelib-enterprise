package com.myhomelibcorp.infrastructure.importer.archive;

import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.shared.util.FileNameSupport;

import java.nio.file.Path;
import java.util.Locale;

public final class ArchiveImportSupport {
    private ArchiveImportSupport() { }

    public static boolean isSafeEntryName(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) return false;
        String normalizedText = name.replace('\\', '/');
        if (normalizedText.startsWith("/") || normalizedText.startsWith("//")
                || normalizedText.matches("(?i)^[a-z]:/.*")) return false;
        // Do not ask the host filesystem to encode an archive display name just to
        // validate traversal. That fails for perfectly valid Cyrillic/CJK names under
        // a minimal POSIX locale. Reject parent traversal lexically instead.
        for (String segment : normalizedText.split("/", -1)) {
            if ("..".equals(segment)) return false;
        }
        return true;
    }

    public static boolean isNestedArchive(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".fb2zip") || lower.endsWith(".fb2.zip")
                || lower.endsWith(".cbz") || lower.endsWith(".jar")
                || lower.endsWith(".7z") || lower.endsWith(".rar") || lower.endsWith(".cbr") || lower.endsWith(".inpx")
                || lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tar.xz") || lower.endsWith(".txz") || lower.endsWith(".cpio");
    }


    public static boolean isSupportedBookEntry(String name, ImporterRegistry importerRegistry) {
        if (name == null || importerRegistry == null || isNestedArchive(name)) return false;
        try {
            importerRegistry.findImporter(importerProbePath(name));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static String suffixFor(String entryName) {
        String lower = fileName(entryName).toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String suffix = dot >= 0 ? lower.substring(dot) : ".bin";
        if (suffix.length() > 16 || !suffix.matches("\\.[a-z0-9.]+")) suffix = ".bin";
        return suffix;
    }

    /** Filesystem-independent basename for archive metadata/UI. */
    public static String fileName(String entryName) {
        if (entryName == null || entryName.isBlank()) return "book.bin";
        String result = FileNameSupport.baseName(entryName);
        return result.isBlank() ? "book.bin" : result;
    }

    /** ASCII probe path used only for importer selection by extension. */
    public static Path importerProbePath(String entryName) {
        return Path.of("archive-entry" + suffixFor(entryName));
    }

    public static Book enrich(Book book, Path archivePath, String entryName, long entrySize) {
        String displayName = fileName(entryName);
        BookFile file = new BookFile(
                displayName,
                archivePath.toAbsolutePath().normalize().toString(),
                entryName,
                entrySize > 0 ? entrySize : book.getFileSize(),
                archivePath.toAbsolutePath().normalize().getParent() != null
                        ? archivePath.toAbsolutePath().normalize().getParent().toString() : ""
        );
        return Book.builder()
                .id(book.getId())
                .title(book.getTitle())
                .authors(book.getAuthors())
                .genres(book.getGenres())
                .series(book.getSeries())
                .sequenceNumber(book.getSequenceNumber())
                .metadata(book.getMetadata())
                .file(file)
                .cover(book.getCover())
                .updateDate(book.getUpdateDate())
                .createdAt(book.getCreatedAt())
                .deleted(book.isDeleted())
                .local(true)
                .build();
    }
}
