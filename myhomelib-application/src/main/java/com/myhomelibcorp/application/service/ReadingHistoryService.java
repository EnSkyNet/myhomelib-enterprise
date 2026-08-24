package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.dto.ReadingHistoryItemDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.exchange.ReadingHistoryPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Application facade for Recent/History UI. */
@Service
public class ReadingHistoryService {
    private final ReadingHistoryPort historyPort;
    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;

    public ReadingHistoryService(
            ReadingHistoryPort historyPort,
            BookQueryRepository bookQueryRepository,
            BookMapper bookMapper) {
        this.historyPort = historyPort;
        this.bookQueryRepository = bookQueryRepository;
        this.bookMapper = bookMapper;
    }

    public List<ReadingHistoryItemDto> recent(int limit) {
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
    }

    public void recordOpened(BookId bookId) {
        if (bookId != null) historyPort.recordOpened(bookId);
    }

    public long count() {
        return historyPort.count();
    }

    public void clear() {
        historyPort.clear();
    }
}
