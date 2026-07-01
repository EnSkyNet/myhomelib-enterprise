package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.SearchQueryService;
import com.myhomelibcorp.application.query.BookQuery;
import com.myhomelibcorp.application.query.Pagination;
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

        log.debug("Пошук через Lucene: '{}'", query);
        List<String> ids = searchQueryService.searchBookIds(query, limit);
        if (ids.isEmpty()) {
            log.debug("Lucene не знайшов результатів, використовуємо SQL пошук за текстом");
            BookQuery bookQuery = BookQuery.builder()
                    .text(query)
                    .pagination(Pagination.of(limit, 0))
                    .build();
            return bookQueryRepository.find(bookQuery).stream()
                    .map(bookMapper::toDto)
                    .collect(Collectors.toList());
        }

        List<BookId> bookIds = ids.stream()
                .map(BookId::fromString)
                .collect(Collectors.toList());
        return bookQueryRepository.findByIds(bookIds).stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }
}