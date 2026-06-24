package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.SearchQueryService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchBooksUseCase {

    private final SearchQueryService searchQueryService;
    private final BookQueryRepository bookQueryRepository;

    public List<Book> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return bookQueryRepository.findAll(limit, 0);
        }

        log.debug("Пошук через Lucene (поле authors): '{}'", query);
        List<String> ids = searchQueryService.searchBookIds(query, limit);
        log.debug("Lucene повернув {} ID", ids.size());

        if (!ids.isEmpty()) {
            List<BookId> bookIds = ids.stream().map(BookId::fromString).collect(Collectors.toList());
            List<Book> books = bookQueryRepository.findByIds(bookIds);
            log.debug("Завантажено {} книг за ID", books.size());
            return books;
        }

        // Fallback: пошук через SQL (Java-фільтр)
        log.debug("Lucene не знайшов результатів, використовуємо SQL пошук за автором: '{}'", query);
        return bookQueryRepository.searchByAuthor(query, limit);
    }
}