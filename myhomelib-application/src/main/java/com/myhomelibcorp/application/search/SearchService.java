package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.mapper.GenreMapper;
import com.myhomelibcorp.application.port.out.cache.SearchCache;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.port.out.search.SearchQueryService;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final SearchQueryService searchQueryService;
    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final SearchCache searchCache;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;
    private final AuthorRepository authorRepository;
    private final AuthorMapper authorMapper;
    private final GenreMapper genreMapper;
    private final BookFilterStateService filterStateService;

    public List<BookDto> search(String query, int limit) {
        BookFilterSpec filter = filterStateService.current();
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank() && !filter.isActive()) {
            return List.of();
        }
        // Blank text is intentionally allowed when a unified filter is active: Lucene then
        // executes MatchAllDocsQuery constrained by the same BookFilterSpec used by SQL.
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        if (normalizedQuery.isBlank()) {
            SearchResult filtered = searchQueryService.search(SearchRequest.builder()
                    .text("").filterSpec(filter).limit(safeLimit).build());
            return filtered.isEmpty() ? List.of() : loadBooks(filtered.bookIds());
        }

        String cacheKey = normalizedQuery + "\u001f" + filter.cacheKey() + "\u001fL" + safeLimit;
        List<BookId> cachedIds = searchCache.get(cacheKey);
        if (cachedIds != null && !cachedIds.isEmpty()) {
            log.debug("Пошук '{}' взято з кешу, знайдено {} книг", normalizedQuery, cachedIds.size());
            return loadBooks(cachedIds);
        }

        SearchRequest request = SearchRequest.builder()
                .text(normalizedQuery)
                .filterSpec(filter)
                .limit(safeLimit)
                .build();
        SearchResult result = searchQueryService.search(request);
        if (result.isEmpty()) {
            log.debug("Пошук '{}' не знайшов результатів", normalizedQuery);
            return List.of();
        }

        List<BookId> ids = result.bookIds();
        searchCache.put(cacheKey, ids);
        log.debug("Пошук '{}' знайшов {} книг, збережено в кеш", normalizedQuery, ids.size());
        return loadBooks(ids);
    }


    public List<BookDto> search(SearchRequest request) {
        if (request == null) return List.of();
        SearchRequest effective = withFilter(request,
                request.filterSpec() == null ? filterStateService.current() : request.filterSpec());
        SearchResult result = searchQueryService.search(effective);
        return result.isEmpty() ? List.of() : loadBooks(result.bookIds());
    }

    public PageResult<BookDto> searchPage(SearchRequest request) {
        if (request == null) return PageResult.empty();
        SearchRequest effective = withFilter(request,
                request.filterSpec() == null ? filterStateService.current() : request.filterSpec());
        SearchResult result = searchQueryService.search(effective);
        List<BookDto> books = result.isEmpty() ? List.of() : loadBooks(result.bookIds());
        int size = Math.max(1, result.pageSize());
        return PageResult.of(books, result.totalHits(), result.page(), size);
    }

    private SearchRequest withFilter(SearchRequest base, BookFilterSpec filter) {
        return SearchRequest.builder()
                .text(base.text()).authorId(base.authorId()).genreId(base.genreId())
                .language(base.language()).ratingFrom(base.ratingFrom()).ratingTo(base.ratingTo())
                .yearFrom(base.yearFrom()).yearTo(base.yearTo()).addedFrom(base.addedFrom()).addedTo(base.addedTo())
                .localOnly(base.localOnly()).filterSpec(filter)
                .limit(base.limit()).offset(base.offset()).mode(base.mode())
                .build();
    }

    private List<BookDto> loadBooks(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) return List.of();

        // SQL IN (...) and the cache adapter do not guarantee input ordering.
        // Preserve Lucene relevance/deep-page order explicitly before mapping to UI DTOs.
        Map<BookId, BookDto> byId = new LinkedHashMap<>();
        for (var book : bookQueryRepository.findByIds(ids)) {
            if (book != null && book.getId() != null) byId.put(book.getId(), bookMapper.toDto(book));
        }
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Універсальний пошук із bounded SQL lookup для словників. */
    public Map<String, Object> searchAll(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        BookFilterSpec filter = filterStateService.current();
        if (normalizedQuery.isBlank()) {
            if (!filter.isActive()) {
                return Map.of("authors", List.of(), "series", List.of(), "genres", List.of(), "books", List.of());
            }
            return Map.of(
                    "authors", List.of(),
                    "series", List.of(),
                    "genres", List.of(),
                    "books", search("", 50)
            );
        }

        Map<String, Object> results = new HashMap<>();
        // Автори шукаються bounded SQL-запитом: повний словник авторів не тримаємо в heap.
        List<AuthorDto> authors = authorRepository.searchByName(normalizedQuery, 20).stream()
                .map(authorMapper::toDto)
                .collect(Collectors.toList());
        results.put("authors", authors);

        // Series/genres stay database-backed; do not materialize a full dictionary
        // for a 700k-1M collection merely to return 20 autocomplete items.
        List<String> series = seriesRepository.searchNames(normalizedQuery, 20);
        results.put("series", series);

        List<GenreDto> genres = genreRepository.searchByName(normalizedQuery, 20).stream()
                .map(genreMapper::toDto)
                .toList();
        results.put("genres", genres);

        // Пошук книг через Lucene
        List<BookDto> books = search(normalizedQuery, 50);
        results.put("books", books);

        log.debug("Пошук '{}' завершено: авторів {}, серій {}, жанрів {}, книг {}",
                normalizedQuery, authors.size(), series.size(), genres.size(), books.size());
        return results;
    }
}