package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.SearchQueryService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SearchBooksUseCase {

    private final SearchQueryService searchQueryService;
    private final BookQueryRepository bookQueryRepository;

    public List<Book> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return bookQueryRepository.findAll(limit, 0);
        }
        List<String> ids = searchQueryService.searchBookIds(query, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<BookId> bookIds = ids.stream().map(BookId::fromString).collect(Collectors.toList());
        return bookQueryRepository.findByIds(bookIds);
    }
}