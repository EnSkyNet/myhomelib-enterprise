package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.imports.scanner.LibraryScanner;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.infrastructure.FolderSyncPort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.sync.SyncOptions;
import com.myhomelibcorp.domain.model.sync.SyncResult;
import com.myhomelibcorp.infrastructure.importengine.InpxImportPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class FolderSyncService implements FolderSyncPort {

    private final BookQueryRepository bookQueryRepository;
    private final BookCommandRepository bookCommandRepository;
    private final SearchIndexer searchIndexer;
    private final LibraryScanner libraryScanner;
    private final ImporterRegistry importerRegistry;
    private final InpxImportPipeline inpxImportPipeline;
    private final FolderSyncBookSupport syncSupport = new FolderSyncBookSupport();

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    @Override
    public SyncResult syncFolder(Path directory, SyncOptions options) {
        if (!isSyncing.compareAndSet(false, true)) {
            throw new IllegalStateException("Синхронізація вже виконується");
        }
        if (directory == null) {
            isSyncing.set(false);
            throw new IllegalArgumentException("Каталог синхронізації не задано");
        }

        SyncOptions effective = options == null ? SyncOptions.builder().build() : options;
        Path root = directory.toAbsolutePath().normalize();
        cancelFlag.set(false);
        LocalDateTime startTime = LocalDateTime.now();

        Counters counters = new Counters();
        List<String> errorMessages = new ArrayList<>();
        boolean indexDirty = false;

        try {
            log.info("📂 Початок синхронізації папки: {}", root);
            log.info("📋 Опції: deleteOrphans={}, updateChanged={}, includeSubfolders={}, processArchives={}",
                    effective.isDeleteOrphans(), effective.isUpdateChanged(), effective.isIncludeSubfolders(), effective.isProcessArchives());

            try (Stream<Path> files = libraryScanner.streamSupportedFiles(
                    root,
                    effective.isIncludeSubfolders(),
                    effective.getMaxDepth(),
                    effective.getMaxFileSize())) {

                var iterator = files.iterator();
                long processed = 0;
                while (iterator.hasNext()) {
                    if (cancelFlag.get()) {
                        log.info("⏹ Синхронізацію скасовано");
                        break;
                    }

                    Path file = iterator.next().toAbsolutePath().normalize();
                    processed++;
                    try {
                        FileResult result = processPhysicalFile(file, root, effective);
                        counters.add(result);
                        indexDirty |= result.indexDirty();
                    } catch (Exception e) {
                        counters.errors++;
                        String message = file + ": " + syncSupport.safeMessage(e);
                        errorMessages.add(message);
                        log.error("Помилка обробки файлу {}", file, e);
                    }

                    if (processed % 100 == 0) {
                        log.info("⏳ Оброблено {} фізичних файлів", processed);
                    }
                }
            }

            if (effective.isDeleteOrphans() && !cancelFlag.get()) {
                FileResult orphanResult = deleteMissingPhysicalFiles(root);
                counters.add(orphanResult);
                indexDirty |= orphanResult.indexDirty();
            }

            if (indexDirty) {
                searchIndexer.commit();
                log.info("✅ Пошуковий індекс синхронізації зафіксовано");
            }
        } catch (IOException e) {
            counters.errors++;
            errorMessages.add("Помилка сканування: " + syncSupport.safeMessage(e));
            log.error("❌ Помилка сканування папки {}", root, e);
        } finally {
            isSyncing.set(false);
            cancelFlag.set(false);
        }

        SyncResult result = SyncResult.builder()
                .added(counters.added)
                .updated(counters.updated)
                .deleted(counters.deleted)
                .skipped(counters.skipped)
                .errors(counters.errors)
                .errorMessages(errorMessages)
                .startTime(startTime)
                .endTime(LocalDateTime.now())
                .build();

        log.info("✅ Синхронізацію завершено: {}", result.getSummary());
        return result;
    }

    @Override
    public CompletableFuture<SyncResult> syncFolderAsync(Path directory, SyncOptions options) {
        return CompletableFuture.supplyAsync(() -> syncFolder(directory, options));
    }

    @Override
    public boolean isSyncing() {
        return isSyncing.get();
    }

    @Override
    public void cancelSync() {
        cancelFlag.set(true);
    }

    private FileResult processPhysicalFile(Path file, Path root, SyncOptions options) throws Exception {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (syncSupport.isInpx(lower)) {
            long count = inpxImportPipeline.importFile(file, 1000, root, cancelFlag);
            if (count > 0) {
                // INPX writes directly to the database, so incremental Lucene callbacks are bypassed.
                searchIndexer.rebuildIndex();
                return new FileResult((int) Math.min(Integer.MAX_VALUE, count), 0, 0, 0, false);
            }
            return FileResult.skipped();
        }

        if (syncSupport.isArchive(lower)) {
            if (!options.isProcessArchives()) return FileResult.skipped();
            String relativeArchive = syncSupport.normalizeRelative(root.relativize(file));
            List<Book> existing = bookQueryRepository.findByArchiveContainer(
                    root.toString(), relativeArchive, syncSupport.normalizePath(file.toString()));

            if (existing.isEmpty()) {
                return importNewFile(file, root);
            }
            if (!options.isUpdateChanged() || !syncSupport.archiveChanged(file, existing)) {
                return FileResult.skipped();
            }
            return reconcileArchive(file, root, existing, options.isDeleteOrphans());
        }

        String folder = syncSupport.relativeFolder(root, file);
        Optional<Book> existing = findExistingLoose(root, folder, file);
        if (existing.isEmpty()) {
            return importNewFile(file, root);
        }
        if (!options.isUpdateChanged() || !syncSupport.fileChanged(file, existing.get())) {
            return FileResult.skipped();
        }
        return updateLooseBook(file, root, existing.get());
    }

    private Optional<Book> findExistingLoose(Path root, String relativeFolder, Path file) {
        String name = file.getFileName().toString();
        Optional<Book> found = bookQueryRepository.findByStorage(root.toString(), relativeFolder, name, "");
        if (found.isPresent()) return found;

        String absoluteFolder = file.getParent() == null ? "" : file.getParent().toAbsolutePath().normalize().toString();
        found = bookQueryRepository.findByStorage(root.toString(), absoluteFolder, name, "");
        if (found.isPresent()) return found;

        // Compatibility with early snapshots that imported loose files before collection_root was populated.
        return bookQueryRepository.findByStorage("", absoluteFolder, name, "");
    }

    private FileResult importNewFile(Path file, Path root) throws Exception {
        BookImporterPort importer = importerRegistry.findImporter(file);
        int added = 0;
        try (Stream<Book> books = importer.importBooks(file)) {
            var iterator = books.iterator();
            while (iterator.hasNext()) {
                if (cancelFlag.get()) break;
                Book parsed = iterator.next();
                if (parsed == null) continue;
                Book normalized = syncSupport.normalizeStorage(parsed, file, root, parsed.getArchiveEntry());
                bookCommandRepository.save(normalized);
                searchIndexer.indexBook(normalized);
                added++;
            }
        }
        return added > 0 ? new FileResult(added, 0, 0, 0, true) : FileResult.skipped();
    }

    /**
     * Re-reads metadata for a changed FB2/EPUB/TXT/generic file while keeping the
     * existing book id. User state stored on the row (rate/progress/review/LibID)
     * and state in related tables (groups/bookmarks/reader positions) therefore survives.
     */
    private FileResult updateLooseBook(Path file, Path root, Book existing) throws Exception {
        BookImporterPort importer = importerRegistry.findImporter(file);
        Book parsed;
        try (Stream<Book> books = importer.importBooks(file)) {
            parsed = books.findFirst().orElseThrow(() -> new IOException("Імпортер не повернув книгу: " + file));
        }
        Book normalized = syncSupport.normalizeStorage(parsed, file, root, "");
        Book merged = syncSupport.mergePreservingUserState(existing, normalized, file);
        bookCommandRepository.save(merged);
        searchIndexer.indexBook(merged);
        log.debug("🔄 Оновлено метадані зміненого файлу: {}", file);
        return new FileResult(0, 1, 0, 0, true);
    }

    private FileResult reconcileArchive(Path file, Path root, List<Book> existing, boolean deleteRemoved) throws Exception {
        BookImporterPort importer = importerRegistry.findImporter(file);
        Map<String, Book> byEntry = new HashMap<>();
        Map<String, Book> byLibId = new HashMap<>();
        for (Book old : existing) {
            byEntry.putIfAbsent(syncSupport.normalizeEntry(old.getArchiveEntry()), old);
            if (old.getLibId() != null && !old.getLibId().isBlank()) byLibId.putIfAbsent(old.getLibId(), old);
        }

        Set<String> matchedIds = new HashSet<>();
        int added = 0;
        int updated = 0;
        try (Stream<Book> books = importer.importBooks(file)) {
            var iterator = books.iterator();
            while (iterator.hasNext()) {
                if (cancelFlag.get()) break;
                Book parsed = iterator.next();
                if (parsed == null) continue;
                Book normalized = syncSupport.normalizeStorage(parsed, file, root, parsed.getArchiveEntry());
                Book old = byEntry.get(syncSupport.normalizeEntry(normalized.getArchiveEntry()));
                if (old == null && normalized.getLibId() != null && !normalized.getLibId().isBlank()) {
                    old = byLibId.get(normalized.getLibId());
                }

                if (old != null) {
                    Book merged = syncSupport.mergePreservingUserState(old, normalized, file);
                    bookCommandRepository.save(merged);
                    searchIndexer.indexBook(merged);
                    matchedIds.add(old.getId().asString());
                    updated++;
                } else {
                    bookCommandRepository.save(normalized);
                    searchIndexer.indexBook(normalized);
                    added++;
                }
            }
        }

        int deleted = 0;
        if (deleteRemoved && !cancelFlag.get()) {
            for (Book old : existing) {
                if (matchedIds.contains(old.getId().asString())) continue;
                bookCommandRepository.deleteById(old.getId());
                searchIndexer.deleteBook(old.getId());
                deleted++;
            }
        }
        return new FileResult(added, updated, deleted, 0, added + updated + deleted > 0);
    }

    private FileResult deleteMissingPhysicalFiles(Path root) {
        int deleted = 0;
        int errors = 0;
        try (Stream<Book> books = bookQueryRepository.streamAll()) {
            var iterator = books.iterator();
            while (iterator.hasNext()) {
                if (cancelFlag.get()) break;
                Book book = iterator.next();
                if (book == null || !book.isLocal()) continue;
                Path physical = syncSupport.physicalPath(book, root);
                if (physical == null || !physical.startsWith(root)) continue;
                if (Files.isRegularFile(physical)) continue;
                try {
                    bookCommandRepository.deleteById(book.getId());
                    searchIndexer.deleteBook(book.getId());
                    deleted++;
                } catch (Exception e) {
                    errors++;
                    log.error("Не вдалося видалити orphan {}", book.getId(), e);
                }
            }
        }
        return new FileResult(0, 0, deleted, errors, deleted > 0);
    }

    private record FileResult(int added, int updated, int deleted, int errors, boolean indexDirty) {
        static FileResult skipped() { return new FileResult(0, 0, 0, 0, false); }
    }

    private static final class Counters {
        int added;
        int updated;
        int deleted;
        int skipped;
        int errors;

        void add(FileResult result) {
            added += result.added();
            updated += result.updated();
            deleted += result.deleted();
            errors += result.errors();
            if (result.added() == 0 && result.updated() == 0 && result.deleted() == 0 && result.errors() == 0) skipped++;
        }
    }
}
