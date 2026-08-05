package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.imports.scanner.LibraryScanner;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class FolderSyncService implements FolderSyncPort {

    private final BookQueryRepository bookQueryRepository;
    private final BookCommandRepository bookCommandRepository;
    private final SearchIndexer searchIndexer;
    private final LibraryScanner libraryScanner;
    private final InpxImportPipeline inpxImportPipeline;

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    @Override
    public SyncResult syncFolder(Path directory, SyncOptions options) {
        if (!isSyncing.compareAndSet(false, true)) {
            throw new IllegalStateException("Синхронізація вже виконується");
        }

        cancelFlag.set(false);
        LocalDateTime startTime = LocalDateTime.now();

        int added = 0;
        int updated = 0;
        int deleted = 0;
        int skipped = 0;
        int errors = 0;
        List<String> errorMessages = new ArrayList<>();

        try {
            log.info("📂 Початок синхронізації папки: {}", directory);
            log.info("📋 Опції: deleteOrphans={}, updateChanged={}, includeSubfolders={}",
                    options.isDeleteOrphans(), options.isUpdateChanged(), options.isIncludeSubfolders());

            // 1. Будуємо індекс існуючих книг
            Map<String, Book> existingBooks = buildBookIndex();
            log.info("📚 Знайдено {} книг у бібліотеці", existingBooks.size());

            // 2. Скануємо файлову систему
            List<Path> files = libraryScanner.scan(directory);
            log.info("📄 Знайдено {} файлів для обробки", files.size());

            Map<String, Path> fileMap = buildFileIndex(files, directory);
            int processed = 0;
            int total = fileMap.size();

            // 3. Обробляємо кожен файл
            for (Map.Entry<String, Path> entry : fileMap.entrySet()) {
                if (cancelFlag.get()) {
                    log.info("⏹ Синхронізацію скасовано");
                    break;
                }

                String key = entry.getKey();
                Path file = entry.getValue();
                processed++;

                try {
                    Book existing = existingBooks.remove(key);

                    if (existing == null) {
                        // Новий файл - імпортуємо
                        if (importFile(file)) {
                            added++;
                        } else {
                            skipped++;
                        }
                    } else if (options.isUpdateChanged() && fileChanged(file, existing)) {
                        // Файл змінився - оновлюємо
                        if (updateBook(file, existing)) {
                            updated++;
                        }
                    }
                } catch (Exception e) {
                    log.error("Помилка обробки файлу: {}", file, e);
                    errors++;
                    errorMessages.add(e.getMessage());
                }

                // Логуємо прогрес кожні 100 файлів
                if (processed % 100 == 0) {
                    log.info("⏳ Оброблено {}/{} файлів", processed, total);
                }
            }

            // 4. Видаляємо зайві книги
            if (options.isDeleteOrphans() && !cancelFlag.get()) {
                deleted = deleteOrphanedBooks(existingBooks.values(), options);
                log.info("🗑 Видалено {} зайвих книг", deleted);
            }

            // 5. Оновлюємо індекс
            if (added > 0 || updated > 0) {
                searchIndexer.commit();
                log.info("✅ Індекс оновлено");
            }

        } catch (IOException e) {
            log.error("❌ Помилка сканування папки: {}", directory, e);
            errors++;
            errorMessages.add("Помилка сканування: " + e.getMessage());
        } finally {
            isSyncing.set(false);
            cancelFlag.set(false);
        }

        SyncResult result = SyncResult.builder()
                .added(added)
                .updated(updated)
                .deleted(deleted)
                .skipped(skipped)
                .errors(errors)
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

    // ==================== ПРИВАТНІ МЕТОДИ ====================

    private Map<String, Book> buildBookIndex() {
        Map<String, Book> index = new HashMap<>();
        try {
            List<Book> books = bookQueryRepository.findAll();
            for (Book book : books) {
                String key = buildBookKey(book);
                index.put(key, book);
            }
        } catch (Exception e) {
            log.warn("Помилка побудови індексу книг: {}", e.getMessage());
        }
        return index;
    }

    private Map<String, Path> buildFileIndex(List<Path> files, Path root) {
        Map<String, Path> index = new HashMap<>();
        for (Path file : files) {
            try {
                Path relative = root.relativize(file);
                String key = relative.toString().toLowerCase();
                index.put(key, file);
            } catch (Exception e) {
                log.warn("Помилка додавання файлу до індексу: {}", file, e);
            }
        }
        return index;
    }

    private String buildBookKey(Book book) {
        String folder = book.getFolder();
        String fileName = book.getFileName();
        if (folder != null && !folder.isEmpty()) {
            return Path.of(folder).resolve(fileName).toString().toLowerCase();
        }
        return fileName != null ? fileName.toLowerCase() : "";
    }

    private boolean fileChanged(Path file, Book existing) {
        try {
            long fileSize = Files.size(file);
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            long bookModified = existing.getUpdateDate() != null
                    ? existing.getUpdateDate().toEpochSecond(java.time.ZoneOffset.UTC) * 1000
                    : 0;
            return fileSize != existing.getFileSize() || lastModified > bookModified;
        } catch (IOException e) {
            log.warn("Помилка перевірки змін файлу: {}", file, e);
            return true;
        }
    }

    private boolean importFile(Path file) {
        try {
            log.debug("📥 Імпорт нового файлу: {}", file);
            long count = inpxImportPipeline.importFile(file, 1000, file.getParent());
            return count > 0;
        } catch (Exception e) {
            log.error("Помилка імпорту файлу: {}", file, e);
            return false;
        }
    }

    private boolean updateBook(Path file, Book existing) {
        try {
            log.debug("🔄 Оновлення книги: {}", existing.getTitle());
            String fileName = file.getFileName().toString().toLowerCase();
            if (fileName.endsWith(".inpx") || fileName.endsWith(".inp")) {
                bookCommandRepository.deleteById(existing.getId());
                long count = inpxImportPipeline.importFile(file, 1000, file.getParent());
                return count > 0;
            }
            return false;
        } catch (Exception e) {
            log.error("Помилка оновлення книги: {}", existing.getId(), e);
            return false;
        }
    }

    private int deleteOrphanedBooks(Iterable<Book> orphanedBooks, SyncOptions options) {
        int deleted = 0;
        for (Book book : orphanedBooks) {
            if (cancelFlag.get()) {
                break;
            }
            try {
                log.debug("🗑 Видалення зайвої книги: {}", book.getTitle());
                bookCommandRepository.deleteById(book.getId());
                searchIndexer.deleteBook(book.getId());
                deleted++;
            } catch (Exception e) {
                log.error("Помилка видалення книги: {}", book.getId(), e);
            }
        }
        searchIndexer.commit();
        return deleted;
    }
}