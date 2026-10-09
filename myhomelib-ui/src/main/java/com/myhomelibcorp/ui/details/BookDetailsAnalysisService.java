package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.group.LoadBookGroupsUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.inspection.BookInspectionService;
import com.myhomelibcorp.reader.inspection.DocumentInspection;
import com.myhomelibcorp.reader.inspection.DocumentInspectionSession;
import com.myhomelibcorp.ui.details.model.RichBookDetailsSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookDetailsAnalysisService {

    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final ResolveBookContentUseCase resolveBookContentUseCase;
    private final LoadBookGroupsUseCase loadBookGroupsUseCase;
    private final BookInspectionService inspectionService = new BookInspectionService();

    public RichBookDetailsSession analyze(String bookId) {
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("Book id is required");
        long started = System.nanoTime();
        checkCancelled();
        BookDto fullBook = loadBookByIdUseCase.execute(BookId.fromString(bookId))
                .orElseThrow(() -> new IllegalArgumentException(UiText.text("ui.java.book_details_analysis_service.7e6db1b645") + bookId));
        log.info("BOOK_DETAILS_START title='{}' id={} file='{}' archiveEntry='{}' format={} sizeBytes={}",
                safeTitle(fullBook), fullBook.getId(), safeLog(fullBook.getFileName()),
                safeLog(fullBook.getArchiveEntry()), formatOf(fullBook), fullBook.getFileSize());

        var groups = loadBookGroupsUseCase.execute(fullBook.getId());
        checkCancelled();

        ResolvedBookContent source = null;
        DocumentInspectionSession inspection = null;
        boolean transferred = false;
        try {
            source = resolveBookContentUseCase.execute(
                    fullBook, ResolveBookContentUseCase.DETAILS_EXTENSIONS);
            checkCancelled();
            inspection = inspectionService.inspect(
                    new FileBookSource(source.path(), fullBook.getId()));
            checkCancelled();
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            log.info("BOOK_DETAILS_DONE title='{}' id={} file='{}' archiveEntry='{}' durationMs={} format={} sizeBytes={}",
                    safeTitle(fullBook), fullBook.getId(), safeLog(fullBook.getFileName()),
                    safeLog(fullBook.getArchiveEntry()), durationMs,
                    inspection.inspection().format(), fullBook.getFileSize());
            RichBookDetailsSession result = new RichBookDetailsSession(
                    fullBook, inspection.inspection(), groups, inspection, source);
            transferred = true;
            return result;
        } catch (java.util.concurrent.CancellationException cancelled) {
            log.info("BOOK_DETAILS_CANCEL title='{}' id={} durationMs={}",
                    safeTitle(fullBook), fullBook.getId(), (System.nanoTime() - started) / 1_000_000L);
            throw cancelled;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || e instanceof java.io.InterruptedIOException) {
                log.info("BOOK_DETAILS_CANCEL title='{}' id={} durationMs={}",
                        safeTitle(fullBook), fullBook.getId(), (System.nanoTime() - started) / 1_000_000L);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("Book details analysis cancelled");
            }
            DocumentInspection unavailable = DocumentInspection.unsupported(formatOf(fullBook),
                    UiText.text("ui.java.book_details_analysis_service.a923ac5c4b") + safeMessage(e));
            return new RichBookDetailsSession(fullBook, unavailable, groups, null, null);
        } finally {
            if (!transferred) {
                if (inspection != null) inspection.close();
                if (source != null) source.close();
            }
        }
    }

    private static String formatOf(BookDto book) {
        String name = book.getArchiveEntry();
        if (name == null || name.isBlank()) name = book.getFileName();
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "";
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Book details analysis cancelled");
        }
    }

    private static String safeTitle(BookDto book) {
        if (book == null || book.getTitle() == null) return "";
        return book.getTitle().replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String safeLog(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String safeMessage(Exception e) {
        return UiExceptionSupport.message(e);
    }
}
