package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Storage/path normalization and metadata merge policy for folder sync.
 * Keeping this deterministic policy outside the orchestration service makes
 * sync control flow smaller without changing catalogue/user-state semantics.
 */
@Slf4j
final class FolderSyncBookSupport {
    Book normalizeStorage(Book parsed, Path physicalFile, Path root, String archiveEntry) throws IOException {
        boolean archived = archiveEntry != null && !archiveEntry.isBlank();
        String folder;
        String fileName;
        long size;
        String entry;

        if (archived) {
            folder = normalizeRelative(root.relativize(physicalFile));
            entry = archiveEntry.replace('\\', '/');
            fileName = parsed.getFileName();
            if (fileName == null || fileName.isBlank()) {
                try { fileName = Path.of(entry).getFileName().toString(); }
                catch (Exception ignored) { fileName = entry; }
            }
            size = parsed.getFileSize();
        } else {
            folder = relativeFolder(root, physicalFile);
            entry = "";
            fileName = physicalFile.getFileName().toString();
            size = safeAttributes(physicalFile, "Folder-sync source").size();
        }

        BookFile newFile = new BookFile(fileName, folder, entry, size, root.toString());
        return parsed.toBuilder()
                .file(newFile)
                .updateDate(sourceTimestamp(physicalFile, parsed.getUpdateDate()))
                .local(true)
                .missingSince(null)
                .build();
    }

    boolean fileChanged(Path file, Book existing) {
        try {
            BasicFileAttributes attrs = safeAttributes(file, "Folder-sync source");
            if (attrs.size() != existing.getFileSize()) return true;
            return attrs.lastModifiedTime().toMillis() > toEpochMillis(existing.getUpdateDate());
        } catch (IOException e) {
            log.warn("Помилка перевірки змін файлу {}: {}", file, e.getMessage());
            return true;
        }
    }

    boolean archiveChanged(Path file, List<Book> existing) {
        try {
            long modified = safeAttributes(file, "Folder-sync archive").lastModifiedTime().toMillis();
            long newestCatalogTimestamp = existing.stream()
                    .map(Book::getUpdateDate)
                    .filter(java.util.Objects::nonNull)
                    .mapToLong(this::toEpochMillis)
                    .max().orElse(0L);
            return modified > newestCatalogTimestamp;
        } catch (IOException e) {
            return true;
        }
    }

    Path physicalPath(Book book, Path syncRoot) {
        try {
            String rootText = book.getCollectionRoot();
            Path collectionRoot = rootText == null || rootText.isBlank()
                    ? syncRoot : Path.of(rootText).toAbsolutePath().normalize();
            String folderText = book.getFolder() == null ? "" : book.getFolder();
            Path folder = folderText.isBlank() ? Path.of("") : Path.of(folderText);

            if (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) {
                Path archive = folder.isAbsolute() ? folder : collectionRoot.resolve(folder);
                return archive.toAbsolutePath().normalize();
            }

            Path parent = folder.isAbsolute() ? folder : collectionRoot.resolve(folder);
            return parent.resolve(book.getFileName()).toAbsolutePath().normalize();
        } catch (Exception e) {
            log.debug("Не вдалося визначити фізичний шлях книги {}: {}", book.getId(), e.getMessage());
            return null;
        }
    }

    String relativeFolder(Path root, Path file) {
        Path parent = file.getParent();
        if (parent == null || parent.equals(root)) return "";
        return normalizeRelative(root.relativize(parent));
    }

    String normalizeRelative(Path path) {
        String value = path == null ? "" : path.toString().replace('\\', '/');
        return ".".equals(value) ? "" : value;
    }

    String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    String normalizeEntry(String value) {
        String v = normalizePath(value).trim();
        while (v.startsWith("/")) v = v.substring(1);
        return v.toLowerCase(Locale.ROOT);
    }

    boolean isInpx(String name) {
        return name.endsWith(".inpx") || name.endsWith(".inp");
    }

    boolean isArchive(String name) {
        return name.endsWith(".zip") || name.endsWith(".fb2zip") || name.endsWith(".fb2.zip")
                || name.endsWith(".cbz") || name.endsWith(".jar") || name.endsWith(".7z")
                || name.endsWith(".rar") || name.endsWith(".cbr") || name.endsWith(".tar")
                || name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".tar.bz2")
                || name.endsWith(".tbz2") || name.endsWith(".tar.xz") || name.endsWith(".txz")
                || name.endsWith(".cpio");
    }

    String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private LocalDateTime sourceTimestamp(Path file, LocalDateTime fallback) {
        try {
            return LocalDateTime.ofInstant(safeAttributes(file, "Folder-sync source").lastModifiedTime().toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            log.debug("Не вдалося прочитати last-modified для {}: {}", file, e.getMessage());
            return fallback;
        }
    }

    private BasicFileAttributes safeAttributes(Path file, String role) throws IOException {
        Path safe = SafeFilePublication.requireSafeRegularFile(file, role);
        BasicFileAttributes attrs = Files.readAttributes(safe, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException(role + " is not a regular file: " + safe);
        return attrs;
    }

    private long toEpochMillis(LocalDateTime value) {
        if (value == null) return 0;
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

}
