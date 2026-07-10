package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.cache.SearchCache;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchQueryService;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final SearchQueryService searchQueryService;
    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final SearchCache searchCache;

    public List<BookDto> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // Перевіряємо кеш
        List<BookId> cachedIds = searchCache.get(query);
        if (cachedIds != null && !cachedIds.isEmpty()) {
            return loadBooks(cachedIds);
        }

        SearchRequest request = SearchRequest.builder()
                .text(query)
                .limit(limit)
                .build();
        SearchResult result = searchQueryService.search(request);
        if (result.isEmpty()) {
            return List.of();
        }

        List<BookId> ids = result.bookIds();
        searchCache.put(query, ids);
        return loadBooks(ids);
    }

    private List<BookDto> loadBooks(List<BookId> ids) {
        return bookQueryRepository.findByIds(ids).stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }
}