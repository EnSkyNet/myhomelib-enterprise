package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.archive.ArchiveEntryNameSupport;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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

    private final ArchiveReader archiveReader;

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
            if (archivePath != null) {
                Optional<String> resolvedEntry = resolveArchiveEntry(archivePath, archiveEntry, fileName);
                if (resolvedEntry.isPresent()) {
                    if (ArchiveEntryNameSupport.sameLogicalPath(resolvedEntry.get(), archiveEntry)) {
                        log.debug("Знайдено архів і запис '{}': {}", archiveEntry, archivePath);
                    } else {
                        log.info("Знайдено server-renamed/legacy archive entry: '{}' -> '{}' у {}",
                                archiveEntry, resolvedEntry.get(), archivePath);
                    }
                    return Optional.of(archivePath);
                }
                log.debug("Архів існує, але запис '{}' відсутній: {}", archiveEntry, archivePath);
            }
            return Optional.empty();
        }

        // 2. Якщо fileName є архівом
        if (fileName != null && isArchive(fileName)) {
            Path archivePath = safeRegularPath(buildFilePath(collectionRoot, folder, fileName), "Book archive");
            if (archivePath != null) {
                log.debug("Знайдено архів (fileName): {}", archivePath);
                return Optional.of(archivePath);
            }
        }

        // 3. Якщо folder є архівом
        if (folder != null && isArchive(folder)) {
            Path archivePath = safeRegularPath(buildFilePath(collectionRoot, null, folder), "Book archive");
            if (archivePath != null) {
                log.debug("Знайдено архів (folder): {}", archivePath);
                return Optional.of(archivePath);
            }
        }

        // 4. Звичайний файл
        Path filePath = safeRegularPath(buildFilePath(collectionRoot, folder, fileName), "Book file");
        if (filePath != null && !isArchive(filePath.toString())) {
            log.debug("Знайдено файл: {}", filePath);
            return Optional.of(filePath);
        }

        log.debug("Файл не знайдено: fileName='{}', folder='{}'", fileName, folder);
        return Optional.empty();
    }

    @Override
    public Optional<Path> locateBookContainer(Book book) {
        if (book == null) return Optional.empty();
        String archiveEntry = book.getArchiveEntry();
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            Path archivePath = findArchivePath(book.getFileName(), book.getFolder(), book.getCollectionRoot());
            return archivePath == null ? Optional.empty() : Optional.of(archivePath);
        }
        return locateBookFile(book);
    }

    @Override
    public Optional<String> materializeArchiveBookEntry(Book book, Path archivePath, Path target, long maxBytes,
                                                        BooleanSupplier cancelled) throws IOException {
        if (book == null || archivePath == null || target == null) return Optional.empty();
        String requestedEntry = book.getArchiveEntry();
        Path safeArchive = safeRegularPath(archivePath, "Book archive");
        if (requestedEntry == null || requestedEntry.isBlank() || safeArchive == null) {
            return Optional.empty();
        }
        String actualEntry;
        try {
            actualEntry = resolveArchiveEntry(safeArchive, requestedEntry, book.getFileName()).orElse(null);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (RuntimeException e) {
            throw new IOException("Не вдалося визначити archive entry у " + archivePath, e);
        }
        if (actualEntry == null) return Optional.empty();
        if (!ArchiveEntryNameSupport.sameLogicalPath(actualEntry, requestedEntry)) {
            log.info("Матеріалізація сумісного archive entry: '{}' -> '{}' у {}",
                    requestedEntry, actualEntry, safeArchive);
        }
        if (!archiveReader.materializeEntry(safeArchive, actualEntry, target, maxBytes, cancelled)) {
            return Optional.empty();
        }
        return Optional.of(actualEntry);
    }

    private Path findArchivePath(String fileName, String folder, String collectionRoot) {
        // Спроба 1: folder як архів
        if (folder != null && !folder.isBlank() && isArchive(folder)) {
            Path safe = safeRegularPath(buildFilePath(collectionRoot, null, folder), "Book archive");
            if (safe != null) return safe;
        }

        // Спроба 2: fileName як архів
        if (fileName != null && !fileName.isBlank() && isArchive(fileName)) {
            Path safe = safeRegularPath(buildFilePath(collectionRoot, folder, fileName), "Book archive");
            if (safe != null) return safe;
        }

        // Спроба 3: folder + fileName як архів
        if (folder != null && !folder.isBlank() && fileName != null && !fileName.isBlank()) {
            Path combined = buildFilePath(collectionRoot, folder, fileName);
            if (combined != null && isArchive(combined.toString())) {
                Path safe = safeRegularPath(combined, "Book archive");
                if (safe != null) return safe;
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
                if (archivePath != null) {
                    String actualEntry = resolveArchiveEntry(archivePath, archiveEntry, fileName).orElse(null);
                    if (actualEntry != null) {
                        if (!ArchiveEntryNameSupport.sameLogicalPath(actualEntry, archiveEntry)) {
                            log.info("Читання сумісного archive entry: '{}' -> '{}' у {}",
                                    archiveEntry, actualEntry, archivePath);
                        }
                        log.debug("Читання з архіву: {}, запис: {}", archivePath, actualEntry);
                        return archiveReader.readEntry(archivePath, actualEntry);
                    }
                    log.debug("Не знайдено однозначного запису '{}' в архіві {}", archiveEntry, archivePath);
                    return Optional.empty();
                }
            }

            // 2. Якщо fileName або folder є архівом
            Path archivePath = findArchivePath(fileName, folder, collectionRoot);
            if (archivePath != null) {
                log.debug("Пошук підтримуваного документа в архіві: {}", archivePath);
                return archiveReader.findFirstEntry(archivePath,
                        e -> { String n = e.toLowerCase(java.util.Locale.ROOT); return n.endsWith(".fb2") || n.endsWith(".fbd") || n.endsWith(".epub") || n.endsWith(".txt") || n.endsWith(".text"); });
            }

            // 3. Звичайний файл
            Path filePath = safeRegularPath(buildFilePath(collectionRoot, folder, fileName), "Book file");
            if (filePath != null) {
                log.debug("Читання файлу: {}", filePath);
                return Optional.of(Files.newInputStream(filePath, LinkOption.NOFOLLOW_LINKS));
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
    private Optional<String> resolveArchiveEntry(Path archivePath, String requestedEntry, String fileName) {
        List<String> entries = archiveReader.listEntries(archivePath);
        if (entries.isEmpty()) return Optional.empty();

        // Physical/raw identity wins. Legacy #Uhhhh decoding is only a compatibility fallback:
        // two distinct archive members can otherwise collapse to the same logical Unicode path.
        // ZIP member names are case-sensitive, even on Windows. If both
        // chapter.fb2 and Chapter.fb2 exist, prefer the physical exact spelling:
        // case-insensitive findFirst() would otherwise return the wrong book.
        Optional<String> physicalExact = entries.stream()
                .filter(entry -> entry.equals(requestedEntry)).findFirst();
        if (physicalExact.isPresent()) return physicalExact;

        String normalizedRequested = ArchiveEntryNameSupport.normalizePath(requestedEntry);
        // Legacy catalogs may use backslashes or a leading slash. Preserve the
        // exact letter case even when only the separator spelling differs.
        List<String> sameCase = entries.stream()
                .filter(entry -> ArchiveEntryNameSupport.normalizePath(entry)
                        .equals(normalizedRequested))
                .limit(2).toList();
        if (sameCase.size() > 1) return Optional.empty();
        if (!sameCase.isEmpty()) return Optional.of(sameCase.getFirst());

        List<String> normalizedMatches = entries.stream()
                .filter(entry -> ArchiveEntryNameSupport.normalizePath(entry)
                        .equalsIgnoreCase(normalizedRequested))
                .limit(2).toList();
        // A case-insensitive collision must not silently pick an arbitrary
        // member or fall back to another unrelated FB2.
        if (normalizedMatches.size() > 1) return Optional.empty();
        if (!normalizedMatches.isEmpty()) return Optional.of(normalizedMatches.getFirst());

        Optional<String> legacyEquivalent = uniqueEntry(entries,
                entry -> ArchiveEntryNameSupport.sameLogicalPath(entry, requestedEntry));
        if (legacyEquivalent.isPresent()) return legacyEquivalent;

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
        Path safe = safeRegularPath(archivePath, "Book archive");
        return safe == null ? List.of() : archiveReader.listEntries(safe);
    }

    @Override
    public Optional<InputStream> readArchiveEntry(Path archivePath, String entryName) {
        Path safe = safeRegularPath(archivePath, "Book archive");
        return safe == null ? Optional.empty() : archiveReader.readEntry(safe, entryName);
    }

    @Override
    public Optional<InputStream> findFirstArchiveEntry(Path archivePath, Predicate<String> filter) {
        Path safe = safeRegularPath(archivePath, "Book archive");
        return safe == null ? Optional.empty() : archiveReader.findFirstEntry(safe, filter);
    }

    private Path safeRegularPath(Path path, String role) {
        if (path == null) return null;
        try {
            return SafeFilePublication.requireSafeRegularFile(path, role);
        } catch (IOException unsafe) {
            log.debug("Ignoring unsafe {} path {}: {}", role, path, unsafe.getMessage());
            return null;
        }
    }

    @Override
    public StagedDeletion stagePhysicalFileForDeletion(Path path, Path managedRoot, String collectionId, List<BookId> affectedBookIds) throws IOException {
        if (path == null) throw new IllegalArgumentException("Path is required");
        if (managedRoot == null) throw new SecurityException("Managed root is required for physical deletion");

        Path normalizedRoot = managedRoot.toAbsolutePath().normalize();
        if (normalizedRoot.getParent() == null) {
            throw new SecurityException("Filesystem root cannot be used as a managed deletion root: " + normalizedRoot);
        }
        try {
            SafeFilePublication.requireSafeDirectoryPath(normalizedRoot, "Managed deletion root");
        } catch (IOException unsafeRoot) {
            throw new SecurityException("Managed root is not a safe directory: " + normalizedRoot, unsafeRoot);
        }

        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot) || normalizedPath.equals(normalizedRoot)) {
            throw new SecurityException("Refusing to delete a file outside the managed root: " + normalizedPath);
        }

        // Do not canonicalize a caller-controlled deletion path with toRealPath(): that operation
        // follows symlink/reparse parents before the containment decision. requireSafeRegularFile()
        // validates the complete parent chain with NOFOLLOW semantics, so lexical containment plus
        // a no-link chain is the stronger boundary and cannot be redirected outside managedRoot.
        final Path safeFile;
        try {
            safeFile = SafeFilePublication.requireSafeRegularFile(normalizedPath, "Book resource staged deletion");
        } catch (IOException unsafeFile) {
            throw new SecurityException("Book resource is not a safe regular file: " + normalizedPath, unsafeFile);
        }
        Path canonicalRoot = normalizedRoot;
        Path canonicalFile = safeFile;

        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("Stable collection id is required for crash-safe deletion");
        }
        if (affectedBookIds == null || affectedBookIds.isEmpty()) {
            throw new IllegalArgumentException("Affected book ids are required for crash-safe deletion");
        }

        String contentSha256 = SafeFilePublication.sha256(canonicalFile, "book resource staged deletion");
        Path recovery = recoverySibling(canonicalFile);
        // Persist intent before touching the visible book path. If the process terminates after
        // this point, startup recovery reconciles the marker with the committed books.local flags.
        LocalCopyDeletionRecoveryStore.PreparedMarker marker = LocalCopyDeletionRecoveryStore.prepare(
                canonicalFile, recovery, canonicalRoot, collectionId, affectedBookIds, contentSha256);
        boolean removed = false;
        try {
            createRecoveryCopy(canonicalFile, recovery);
            String recoveryHash = SafeFilePublication.sha256(recovery, "book deletion recovery copy");
            if (!contentSha256.equalsIgnoreCase(recoveryHash)) {
                throw new IOException("Recovery copy differs from the book being staged for deletion: " + canonicalFile);
            }
            SafeFilePublication.removeIfOwned(canonicalFile, contentSha256, "book resource staged deletion");
            removed = true;
            if (!removed) throw new IOException("Book resource disappeared before staged deletion: " + canonicalFile);
            return new FileStagedDeletion(canonicalFile, recovery, marker, contentSha256);
        } catch (IOException | RuntimeException error) {
            if (!removed) {
                try {
                    if (Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) {
                        SafeFilePublication.removeIfOwned(recovery, contentSha256, "failed book deletion recovery cleanup");
                    }
                }
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
        SafeFilePublication.prepareTarget(recovery, "book deletion recovery target");
        if (Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(recovery.toString());
        }
        try {
            Files.createLink(recovery, original);
            return;
        } catch (UnsupportedOperationException | IOException hardLinkFailure) {
            try (InputStream in = Files.newInputStream(original, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.copyFresh(in, recovery, "book deletion recovery copy");
            } catch (IOException copyFailure) {
                copyFailure.addSuppressed(hardLinkFailure);
                throw copyFailure;
            }
        }
    }

    private static final class FileStagedDeletion implements StagedDeletion {
        private final Path original;
        private final Path recovery;
        private final LocalCopyDeletionRecoveryStore.PreparedMarker marker;
        private final String contentSha256;
        private boolean finished;

        private FileStagedDeletion(Path original, Path recovery,
                                   LocalCopyDeletionRecoveryStore.PreparedMarker marker,
                                   String contentSha256) {
            this.original = original;
            this.recovery = recovery;
            this.marker = marker;
            this.contentSha256 = contentSha256;
        }

        @Override
        public Path originalPath() { return original; }

        @Override
        public Path recoveryPath() { return recovery; }

        @Override
        public synchronized void commit() throws IOException {
            if (finished) return;
            if (Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.removeIfOwned(recovery, contentSha256, "committed book deletion recovery cleanup");
            }
            LocalCopyDeletionRecoveryStore.clear(marker);
            finished = true;
        }

        @Override
        public synchronized void rollback() throws IOException {
            if (finished) return;
            if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                String visibleHash = SafeFilePublication.sha256(original, "book deletion rollback visible target");
                if (!contentSha256.equalsIgnoreCase(visibleHash)) {
                    throw new IOException("Book deletion rollback target changed; recovery evidence kept: " + original);
                }
                if (Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) {
                    SafeFilePublication.removeIfOwned(recovery, contentSha256, "book deletion rollback duplicate cleanup");
                }
                LocalCopyDeletionRecoveryStore.clear(marker);
                finished = true;
                return;
            }
            if (!Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Recovery copy is missing for staged deletion: " + original);
            }
            String recoveryHash = SafeFilePublication.sha256(recovery, "book deletion rollback recovery bytes");
            if (!contentSha256.equalsIgnoreCase(recoveryHash)) {
                throw new IOException("Book deletion rollback recovery bytes changed: " + recovery);
            }
            SafeFilePublication.moveNoReplace(recovery, original, "book deletion rollback restore");
            String restoredHash = SafeFilePublication.sha256(original, "book deletion rollback restored bytes");
            if (!contentSha256.equalsIgnoreCase(restoredHash)) {
                throw new IOException("Book deletion rollback restored bytes differ: " + original);
            }
            LocalCopyDeletionRecoveryStore.clear(marker);
            finished = true;
        }
    }

    // ==================== Побудова шляхів ====================

    @Override
    public Path buildFilePath(String root, String folder, String fileName) {
        Path candidate;
        if (fileName != null && !fileName.isBlank()) {
            Path fileNamePath = Paths.get(fileName);
            if (fileNamePath.isAbsolute()) {
                candidate = fileNamePath;
                return constrainToCollectionRoot(candidate, root);
            }
        }

        if (folder != null && !folder.isBlank()) {
            Path folderPath = Paths.get(folder);
            if (folderPath.isAbsolute()) {
                candidate = fileName != null && !fileName.isBlank()
                        ? folderPath.resolve(fileName) : folderPath;
                return constrainToCollectionRoot(candidate, root);
            }
        }

        if (root != null && !root.isBlank() && folder != null && !folder.isBlank()) {
            Path rootPath = Paths.get(root);
            Path folderPath = Paths.get(folder);
            candidate = fileName != null && !fileName.isBlank()
                    ? rootPath.resolve(folderPath).resolve(fileName) : rootPath.resolve(folderPath);
            return constrainToCollectionRoot(candidate, root);
        }

        if (root != null && !root.isBlank() && fileName != null && !fileName.isBlank()) {
            candidate = Paths.get(root).resolve(fileName);
            return constrainToCollectionRoot(candidate, root);
        }

        if (fileName != null && !fileName.isBlank()) {
            return Paths.get(fileName).normalize();
        }

        if (folder != null && !folder.isBlank()) {
            return Paths.get(folder).normalize();
        }

        return Paths.get(".").normalize();
    }

    /**
     * Catalog metadata is not a filesystem authority. Whenever a collection root is known,
     * a stored file/folder path may point only inside that root. Absolute paths are retained
     * for compatibility with locally imported archives, but only when they resolve inside the
     * same collection root. Existing symlink/reparse escapes are rejected as well.
     */
    private Path constrainToCollectionRoot(Path candidate, String root) {
        if (candidate == null) return null;
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (root == null || root.isBlank()) return normalizedCandidate;

        Path normalizedRoot = Paths.get(root).toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            log.warn("Відхилено шлях ресурсу поза коренем колекції: root={}, path={}", normalizedRoot, normalizedCandidate);
            return null;
        }

        // Lexical containment is insufficient when any component below (or above) the collection
        // root is a symlink/junction. Existing book resources are required to be ordinary files
        // reachable only through ordinary directories. This also prevents a catalog path from
        // being reinterpreted through a symlinked parent immediately before reader/export work.
        try {
            if (Files.exists(normalizedCandidate, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.requireSafeRegularFile(normalizedCandidate, "book resource");
                SafeFilePublication.requireSafeDirectoryPath(normalizedRoot, "collection root");
            }
        } catch (IOException | SecurityException unsafePath) {
            log.warn("Не вдалося безпечно перевірити шлях ресурсу книги: {}", normalizedCandidate, unsafePath);
            return null;
        }
        return normalizedCandidate;
    }
}
