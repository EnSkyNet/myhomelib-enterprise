package com.myhomelibcorp.application.usecase.download;

import com.myhomelibcorp.shared.util.ThrowableMessages;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.application.port.out.download.OnlineBookDownloadPort;
import com.myhomelibcorp.application.port.out.download.DownloadQueuePort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class DownloadBookUseCase {
    private final OnlineBookDownloadPort downloadPort;
    private final DownloadQueuePort downloadQueuePort;
    private final BookCommandRepository bookCommandRepository;
    private final CatalogUpdateTrackingPort catalogUpdateTrackingPort;
    private final StatisticsRepository statisticsRepository;
    private final SearchIndexSynchronizer searchIndexSynchronizer;

    public Path execute(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress) throws Exception {
        return execute(book, collection, cancelFlag, progress, false);
    }

    public Path execute(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress,
                        boolean forceRefresh) throws Exception {
        if (book == null) throw new IllegalArgumentException("Book is required");
        if (collection == null) throw new IllegalStateException("Активну колекцію не вибрано");
        if (collection.getId() == null || collection.getId().isBlank()) throw new IllegalStateException("Колекція не має stable ID");
        BookId bookId = BookId.fromString(book.getId());
        AtomicBoolean cancel = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        String archiveIdentity = physicalIdentity(book);
        String resumeHint = archiveIdentity.isBlank() ? null : archiveIdentity + ".part";

        downloadQueuePort.markPending(collection.getId(), bookId.asString(), archiveIdentity, resumeHint);
        downloadQueuePort.markInProgress(collection.getId(), bookId.asString());
        try {
            var result = downloadPort.download(book, collection, cancel, progress == null ? v -> {} : progress, forceRefresh);
            bookCommandRepository.updateStorage(
                    bookId, result.root().toString(), result.folder(), result.fileName(), result.archiveEntry(), true);
            catalogUpdateTrackingPort.markDownloadedBaseline(bookId);
            searchIndexSynchronizer.synchronizeSafelyNow(java.util.List.of(bookId));
            downloadQueuePort.markCompleted(collection.getId(), bookId.asString(), result.physicalPath());
            invalidateStatisticsSafely();
            return result.physicalPath();
        } catch (Exception error) {
            if (cancel.get() || isCancellation(error)) {
                downloadQueuePort.markCancelled(collection.getId(), bookId.asString(), resumeHint);
            } else {
                downloadQueuePort.markFailed(collection.getId(), bookId.asString(),
                        SensitiveDataSanitizer.sanitizeText(ThrowableMessages.rootMessage(error)), resumeHint);
            }
            throw error;
        }
    }

    private void invalidateStatisticsSafely() {
        try {
            statisticsRepository.invalidate();
        } catch (RuntimeException error) {
            // The book/local-state transaction is already complete. Do not report a false
            // download failure because a derived statistics cache could not be invalidated.
            log.warn("Не вдалося інвалідувати кеш статистики після завантаження", error);
        }
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.util.concurrent.CancellationException) return true;
            String name = current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("cancel") || message.contains("скасовано") || message.contains("cancelled") || message.contains("canceled")) return true;
            current = current.getCause();
        }
        return false;
    }


    private static String physicalIdentity(BookDto book) {
        String folder = clean(book.getFolder());
        String file = clean(book.getFileName());
        if (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) {
            if (!folder.isBlank()) return folder;
            return file;
        }
        return folder.isBlank() ? file : folder + "/" + file;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String v = value.replace('\\', '/').trim();
        while (v.startsWith("/")) v = v.substring(1);
        return v;
    }
}
