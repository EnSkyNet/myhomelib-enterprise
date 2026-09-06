package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.archive.ArchiveEntryNameSupport;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.Locale;

/**
 * Єдиний сервіс для роботи з файлами книг.
 * Інкапсулює всю логіку пошуку, читання та роботи з архівами.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookResourceResolver implements BookResourcePort {

    private final ZipArchiveReader archiveReader;

    private static final List<String> ARCHIVE_EXTENSIONS = List.of(".zip", ".fb2zip", ".fb2.zip", ".cbz", ".jar", ".7z", ".rar", ".cbr", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".cpio");

    // ==================== Пошук файлів ====================

    @Override
    public Optional<Path> locateBookFile(Book book) {
        if (book == null) {
            return Optional.empty();
        }

        String fileName = book.getFileName();
        String folder = book.getFolder();
        String collectionRoot = book.getCollectionRoot();
        String archiveEntry = book.getArchiveEntry();

        return locateBookFile(fileName, folder, collectionRoot, archiveEntry);
    }

    @Override
    public Optional<Path> locateBookFile(String fileName, String folder, String collectionRoot, String archiveEntry) {
        log.debug("locateBookFile: fileName='{}', folder='{}', root='{}', archiveEntry='{}'",
                fileName, folder, collectionRoot, archiveEntry);

        // 1. Якщо є archiveEntry, локальною є лише книга, чий конкретний запис
        // реально присутній в архіві. Сам факт існування shared ZIP недостатній.
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            Path archivePath = findArchivePath(fileName, folder, collectionRoot);
            if (archivePath != null && Files.isRegularFile(archivePath)) {
                if (archiveReader.containsEntry(archivePath, archiveEntry)) {
                    log.debug("Знайдено архів і запис '{}': {}", archiveEntry, archivePath);
                    return Optional.of(archivePath);
                }
                Optional<String> compatible = resolveCompatibleArchiveEntry(archivePath, archiveEntry, fileName);
                if (compatible.isPresent()) {
                    log.info("Знайдено server-renamed/legacy archive entry: '{}' -> '{}' у {}",
                            archiveEntry, compatible.get(), archivePath);
                    return Optional.of(archivePath);
                }
                log.debug("Архів існує, але запис '{}' відсутній: {}", archiveEntry, archivePath);
            }
            return Optional.empty();
        }

        // 2. Якщо fileName є архівом
        if (fileName != null && isArchive(fileName)) {
            Path archivePath = buildFilePath(collectionRoot, folder, fileName);
            if (archivePath != null && Files.isRegularFile(archivePath)) {
                log.debug("Знайдено архів (fileName): {}", archivePath);
                return Optional.of(archivePath);
            }
        }

        // 3. Якщо folder є архівом
        if (folder != null && isArchive(folder)) {
            Path archivePath = buildFilePath(collectionRoot, null, folder);
            if (archivePath != null && Files.isRegularFile(archivePath)) {
                log.debug("Знайдено архів (folder): {}", archivePath);
                return Optional.of(archivePath);
            }
        }

        // 4. Звичайний файл
        Path filePath = buildFilePath(collectionRoot, folder, fileName);
        if (filePath != null && Files.isRegularFile(filePath) && !isArchive(filePath.toString())) {
            log.debug("Знайдено файл: {}", filePath);
            return Optional.of(filePath);
        }

        log.debug("Файл не знайдено: fileName='{}', folder='{}'", fileName, folder);
        return Optional.empty();
    }

    private Path findArchivePath(String fileName, String folder, String collectionRoot) {
        // Спроба 1: folder як архів
        if (folder != null && !folder.isBlank() && isArchive(folder)) {
            return buildFilePath(collectionRoot, null, folder);
        }

        // Спроба 2: fileName як архів
        if (fileName != null && !fileName.isBlank() && isArchive(fileName)) {
            return buildFilePath(collectionRoot, folder, fileName);
        }

        // Спроба 3: folder + fileName як архів
        if (folder != null && !folder.isBlank() && fileName != null && !fileName.isBlank()) {
            Path combined = buildFilePath(collectionRoot, folder, fileName);
            if (combined != null && Files.isRegularFile(combined) && isArchive(combined.toString())) {
                return combined;
            }
        }

        return null;
    }

    // ==================== Читання даних ====================

    @Override
    public Optional<InputStream> readBookData(Book book) {
        if (book == null) {
            return Optional.empty();
        }

        String fileName = book.getFileName();
        String folder = book.getFolder();
        String collectionRoot = book.getCollectionRoot();
        String archiveEntry = book.getArchiveEntry();

        return readBookData(fileName, folder, collectionRoot, archiveEntry);
    }

    @Override
    public Optional<InputStream> readBookData(String fileName, String folder, String collectionRoot, String archiveEntry) {
        try {
            // 1. Якщо є archiveEntry - читаємо з архіву
            if (archiveEntry != null && !archiveEntry.isBlank()) {
                Path archivePath = findArchivePath(fileName, folder, collectionRoot);
                if (archivePath != null && Files.isRegularFile(archivePath)) {
                    String actualEntry = archiveReader.containsEntry(archivePath, archiveEntry)
                            ? archiveEntry
                            : resolveCompatibleArchiveEntry(archivePath, archiveEntry, fileName).orElse(null);
                    if (actualEntry != null) {
                        log.debug("Читання з архіву: {}, запис: {}", archivePath, actualEntry);
                        return archiveReader.readEntry(archivePath, actualEntry);
                    }
                    log.debug("Не знайдено однозначного запису '{}' в архіві {}", archiveEntry, archivePath);
                    return Optional.empty();
                }
            }

            // 2. Якщо fileName або folder є архівом
            Path archivePath = findArchivePath(fileName, folder, collectionRoot);
            if (archivePath != null && Files.isRegularFile(archivePath)) {
                log.debug("Пошук підтримуваного документа в архіві: {}", archivePath);
                return archiveReader.findFirstEntry(archivePath,
                        e -> { String n = e.toLowerCase(java.util.Locale.ROOT); return n.endsWith(".fb2") || n.endsWith(".fbd") || n.endsWith(".epub") || n.endsWith(".txt") || n.endsWith(".text"); });
            }

            // 3. Звичайний файл
            Path filePath = buildFilePath(collectionRoot, folder, fileName);
            if (filePath != null && Files.isRegularFile(filePath)) {
                log.debug("Читання файлу: {}", filePath);
                return Optional.of(Files.newInputStream(filePath));
            }

            log.debug("Файл книги не знайдено: fileName='{}', folder='{}'", fileName, folder);
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Не вдалося відкрити локальний ресурс книги: fileName='" + fileName + "', folder='" + folder + "'", e);
        }
    }

    /**
     * Backward-compatible resolver for archives downloaded by older builds where the server
     * renamed the FB2 entry but the catalog entry name was persisted unchanged. Exact matches
     * remain preferred; fallback is allowed only when a token match is unique or the archive
     * contains exactly one FB2 document, so shared multi-book archives are never guessed.
     */
    private Optional<String> resolveCompatibleArchiveEntry(Path archivePath, String requestedEntry, String fileName) {
        List<String> entries = archiveReader.listEntries(archivePath);
        if (entries.isEmpty()) return Optional.empty();

        String requested = ArchiveEntryNameSupport.baseName(requestedEntry);
        String file = ArchiveEntryNameSupport.baseName(fileName);
        String requestedStem = ArchiveEntryNameSupport.stripFb2Extension(requested);
        String fileStem = ArchiveEntryNameSupport.stripFb2Extension(file);

        Optional<String> byRequested = uniqueEntry(entries,
                entry -> ArchiveEntryNameSupport.isFb2(entry) && ArchiveEntryNameSupport.containsDelimitedToken(ArchiveEntryNameSupport.baseName(entry), requestedStem));
        if (byRequested.isPresent()) return byRequested;

        if (!fileStem.isBlank() && !fileStem.equalsIgnoreCase(requestedStem)) {
            Optional<String> byFile = uniqueEntry(entries,
                    entry -> ArchiveEntryNameSupport.isFb2(entry) && ArchiveEntryNameSupport.containsDelimitedToken(ArchiveEntryNameSupport.baseName(entry), fileStem));
            if (byFile.isPresent()) return byFile;
        }

        List<String> fb2Entries = entries.stream().filter(ArchiveEntryNameSupport::isFb2).toList();
        return fb2Entries.size() == 1 ? Optional.of(fb2Entries.getFirst()) : Optional.empty();
    }

    private static Optional<String> uniqueEntry(List<String> entries, Predicate<String> predicate) {
        List<String> matches = entries.stream().filter(predicate).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    // ==================== Робота з архівами ====================

    @Override
    public boolean isArchive(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return ARCHIVE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    @Override
    public List<String> listArchiveEntries(Path archivePath) {
        return archiveReader.listEntries(archivePath);
    }

    @Override
    public Optional<InputStream> readArchiveEntry(Path archivePath, String entryName) {
        return archiveReader.readEntry(archivePath, entryName);
    }

    @Override
    public Optional<InputStream> findFirstArchiveEntry(Path archivePath, Predicate<String> filter) {
        return archiveReader.findFirstEntry(archivePath, filter);
    }

    @Override
    public StagedDeletion stagePhysicalFileForDeletion(Path path, Path managedRoot, String collectionId, List<BookId> affectedBookIds) throws IOException {
        if (path == null) throw new IllegalArgumentException("Path is required");
        if (managedRoot == null) throw new SecurityException("Managed root is required for physical deletion");

        Path normalizedRoot = managedRoot.toAbsolutePath().normalize();
        if (normalizedRoot.getParent() == null) {
            throw new SecurityException("Filesystem root cannot be used as a managed deletion root: " + normalizedRoot);
        }
        if (!Files.exists(normalizedRoot)) {
            throw new SecurityException("Managed root does not exist: " + normalizedRoot);
        }

        Path normalizedPath = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedPath)) {
            throw new SecurityException("Refusing to delete a symbolic-link book resource: " + normalizedPath);
        }
        if (!Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Book resource is not a regular file: " + normalizedPath);
        }

        // Resolve parent symlinks on both sides before comparing. This prevents a path that
        // is textually inside the collection root from escaping through a symlinked directory.
        Path canonicalRoot = normalizedRoot.toRealPath();
        Path canonicalFile = normalizedPath.toRealPath();
        if (!canonicalFile.startsWith(canonicalRoot) || canonicalFile.equals(canonicalRoot)) {
            throw new SecurityException("Refusing to delete a file outside the managed root: " + canonicalFile);
        }

        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("Stable collection id is required for crash-safe deletion");
        }
        if (affectedBookIds == null || affectedBookIds.isEmpty()) {
            throw new IllegalArgumentException("Affected book ids are required for crash-safe deletion");
        }

        Path recovery = recoverySibling(canonicalFile);
        // Persist intent before touching the visible book path. If the process terminates after
        // this point, startup recovery reconciles the marker with the committed books.local flags.
        Path marker = LocalCopyDeletionRecoveryStore.prepare(canonicalFile, recovery, canonicalRoot, collectionId, affectedBookIds);
        boolean removed = false;
        try {
            createRecoveryCopy(canonicalFile, recovery);
            removed = Files.deleteIfExists(canonicalFile);
            if (!removed) throw new IOException("Book resource disappeared before staged deletion: " + canonicalFile);
            return new FileStagedDeletion(canonicalFile, recovery, marker);
        } catch (IOException | RuntimeException error) {
            if (!removed) {
                try { Files.deleteIfExists(recovery); }
                catch (IOException cleanup) { error.addSuppressed(cleanup); }
                try { LocalCopyDeletionRecoveryStore.clear(marker); }
                catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }

    private static Path recoverySibling(Path original) {
        String fileName = original.getFileName() == null ? "book" : original.getFileName().toString();
        return original.resolveSibling("." + fileName + ".mhl-delete-" + UUID.randomUUID() + ".recovery");
    }

    private static void createRecoveryCopy(Path original, Path recovery) throws IOException {
        try {
            Files.createLink(recovery, original);
            return;
        } catch (UnsupportedOperationException | IOException hardLinkFailure) {
            try {
                Files.copy(original, recovery, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException copyFailure) {
                copyFailure.addSuppressed(hardLinkFailure);
                throw copyFailure;
            }
        }
    }

    private static final class FileStagedDeletion implements StagedDeletion {
        private final Path original;
        private final Path recovery;
        private final Path marker;
        private boolean finished;

        private FileStagedDeletion(Path original, Path recovery, Path marker) {
            this.original = original;
            this.recovery = recovery;
            this.marker = marker;
        }

        @Override
        public Path originalPath() { return original; }

        @Override
        public Path recoveryPath() { return recovery; }

        @Override
        public synchronized void commit() throws IOException {
            if (finished) return;
            Files.deleteIfExists(recovery);
            LocalCopyDeletionRecoveryStore.clear(marker);
            finished = true;
        }

        @Override
        public synchronized void rollback() throws IOException {
            if (finished) return;
            if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(recovery);
                LocalCopyDeletionRecoveryStore.clear(marker);
                finished = true;
                return;
            }
            if (!Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Recovery copy is missing for staged deletion: " + original);
            }
            try {
                Files.move(recovery, original, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(recovery, original);
            }
            LocalCopyDeletionRecoveryStore.clear(marker);
            finished = true;
        }
    }

    // ==================== Побудова шляхів ====================

    @Override
    public Path buildFilePath(String root, String folder, String fileName) {
        if (fileName != null && !fileName.isBlank()) {
            Path fileNamePath = Paths.get(fileName);
            if (fileNamePath.isAbsolute()) {
                return fileNamePath;
            }
        }

        if (folder != null && !folder.isBlank()) {
            Path folderPath = Paths.get(folder);
            if (folderPath.isAbsolute()) {
                if (fileName != null && !fileName.isBlank()) {
                    return folderPath.resolve(fileName);
                }
                return folderPath;
            }
        }

        if (root != null && !root.isBlank() && folder != null && !folder.isBlank()) {
            Path rootPath = Paths.get(root);
            Path folderPath = Paths.get(folder);
            if (fileName != null && !fileName.isBlank()) {
                return rootPath.resolve(folderPath).resolve(fileName);
            }
            return rootPath.resolve(folderPath);
        }

        if (root != null && !root.isBlank() && fileName != null && !fileName.isBlank()) {
            return Paths.get(root).resolve(fileName);
        }

        if (fileName != null && !fileName.isBlank()) {
            return Paths.get(fileName);
        }

        if (folder != null && !folder.isBlank()) {
            return Paths.get(folder);
        }

        return Paths.get(".");
    }
}
