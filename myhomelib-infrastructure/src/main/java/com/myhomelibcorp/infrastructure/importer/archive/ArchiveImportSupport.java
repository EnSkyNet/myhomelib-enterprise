package com.myhomelibcorp.infrastructure.importer.archive;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;

import java.nio.file.Path;
import java.util.Locale;

public final class ArchiveImportSupport {
    private ArchiveImportSupport() { }

    public static boolean isSafeEntryName(String name) {
        if (name == null || name.isBlank()) return false;
        String normalizedText = name.replace('\\', '/');
        if (normalizedText.startsWith("/") || normalizedText.startsWith("../") || normalizedText.contains("/../")) {
            return false;
        }
        try {
            Path path = Path.of(normalizedText).normalize();
            return !path.isAbsolute() && !path.startsWith("..");
        } catch (Exception e) {
            return false;
        }
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

    public static String suffixFor(String entryName) {
        String fileName;
        try {
            fileName = Path.of(entryName.replace('\\', '/')).getFileName().toString();
        } catch (Exception e) {
            fileName = "book.bin";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String suffix = dot >= 0 ? lower.substring(dot) : ".bin";
        if (suffix.length() > 16 || !suffix.matches("\\.[a-z0-9.]+")) suffix = ".bin";
        return suffix;
    }

    public static Book enrich(Book book, Path archivePath, String entryName, long entrySize) {
        String displayName;
        try {
            displayName = Path.of(entryName.replace('\\', '/')).getFileName().toString();
        } catch (Exception e) {
            displayName = entryName;
        }
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
