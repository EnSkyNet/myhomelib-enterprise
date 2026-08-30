package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
            size = Files.size(physicalFile);
        }

        BookFile newFile = new BookFile(fileName, folder, entry, size, root.toString());
        return Book.builder()
                .id(parsed.getId())
                .title(parsed.getTitle())
                .authors(new ArrayList<>(parsed.getAuthors()))
                .genres(new ArrayList<>(parsed.getGenres()))
                .series(parsed.getSeries())
                .sequenceNumber(parsed.getSequenceNumber())
                .metadata(parsed.getMetadata())
                .file(newFile)
                .cover(parsed.getCover())
                .updateDate(fileTimestamp(physicalFile))
                .createdAt(parsed.getCreatedAt())
                .deleted(parsed.isDeleted())
                .local(true)
                .build();
    }

    boolean fileChanged(Path file, Book existing) {
        try {
            if (Files.size(file) != existing.getFileSize()) return true;
            return Files.getLastModifiedTime(file).toMillis() > toEpochMillis(existing.getUpdateDate());
        } catch (IOException e) {
            log.warn("Помилка перевірки змін файлу {}: {}", file, e.getMessage());
            return true;
        }
    }

    boolean archiveChanged(Path file, List<Book> existing) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
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

    private LocalDateTime fileTimestamp(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            return LocalDateTime.now();
        }
    }

    private long toEpochMillis(LocalDateTime value) {
        if (value == null) return 0;
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String preferParsed(String parsed, String existing) {
        return parsed != null && !parsed.isBlank() ? parsed : (existing == null ? "" : existing);
    }
}
