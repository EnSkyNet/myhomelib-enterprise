package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchQueryService;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
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
    private final BookMapper bookMapper;

    public List<BookDto> execute(String query, int limit) {
        if (query == null || query.isBlank()) {
            BookQuery bookQuery = BookQuery.builder()
                    .pagination(Pagination.of(limit, 0))
                    .build();
            return bookQueryRepository.find(bookQuery).stream()
                    .map(bookMapper::toDto)
                    .collect(Collectors.toList());
        }

        SearchRequest request = SearchRequest.builder()
                .text(query)
                .limit(limit)
                .build();

        SearchResult result = searchQueryService.search(request);
        if (result.isEmpty()) {
            log.debug("Lucene не знайшов результатів, використовуємо SQL пошук за текстом");
            BookQuery bookQuery = BookQuery.builder()
                    .text(query)
                    .pagination(Pagination.of(limit, 0))
                    .build();
            return bookQueryRepository.find(bookQuery).stream()
                    .map(bookMapper::toDto)
                    .collect(Collectors.toList());
        }

        List<BookId> bookIds = result.bookIds();
        return bookQueryRepository.findByIds(bookIds).stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }
}