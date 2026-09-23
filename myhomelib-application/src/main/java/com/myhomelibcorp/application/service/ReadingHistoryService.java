package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.dto.ReadingHistoryItemDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.exchange.ReadingHistoryPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Application facade for Recent/History UI. */
@Service
public class ReadingHistoryService {
    private final ReadingHistoryPort historyPort;
    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/callers without lifecycle wiring. */
    public ReadingHistoryService(
            ReadingHistoryPort historyPort,
            BookQueryRepository bookQueryRepository,
            BookMapper bookMapper) {
        this(historyPort, bookQueryRepository, bookMapper, null);
    }

    @Autowired
    public ReadingHistoryService(
            ReadingHistoryPort historyPort,
            BookQueryRepository bookQueryRepository,
            BookMapper bookMapper,
            LibraryOperationCoordinator operationCoordinator) {
        this.historyPort = historyPort;
        this.bookQueryRepository = bookQueryRepository;
        this.bookMapper = bookMapper;
        this.operationCoordinator = operationCoordinator;
    }

    public List<ReadingHistoryItemDto> recent(int limit) {
        return withReaderState(() -> {
            int safeLimit = Math.max(1, Math.min(limit, 500));
            List<ReadingHistoryPort.Entry> entries = historyPort.recent(safeLimit);
            if (entries.isEmpty()) return List.of();

            Map<BookId, Book> books = new HashMap<>();
            for (Book book : bookQueryRepository.findByIds(entries.stream().map(ReadingHistoryPort.Entry::bookId).toList())) {
                books.put(book.getId(), book);
            }

            return entries.stream()
                    .filter(entry -> books.containsKey(entry.bookId()))
                    .map(entry -> new ReadingHistoryItemDto(
                            bookMapper.toDto(books.get(entry.bookId())), entry.lastOpenedAt()))
                    .toList();
        });
    }

    public void recordOpened(BookId bookId) {
        if (bookId != null) withReaderState(() -> historyPort.recordOpened(bookId));
    }

    public long count() {
        return withReaderState(historyPort::count);
    }

    public void clear() {
        withReaderState(historyPort::clear);
    }

    private <T> T withReaderState(Supplier<T> action) {
        if (operationCoordinator == null) return action.get();
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.READER_STATE);
        try {
            T result = action.get();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private void withReaderState(Runnable action) {
        withReaderState(() -> {
            action.run();
            return null;
        });
    }
}
