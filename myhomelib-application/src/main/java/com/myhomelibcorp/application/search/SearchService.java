package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.mapper.GenreMapper;
import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.cache.SearchCache;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.search.SearchQueryService;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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
    private final DictionaryCachePort dictionaryCache;
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
        if (normalizedQuery.isBlank()) {
            SearchResult filtered = searchQueryService.search(SearchRequest.builder()
                    .text("").filterSpec(filter).limit(limit).build());
            return filtered.isEmpty() ? List.of() : loadBooks(filtered.bookIds());
        }

        String cacheKey = normalizedQuery + "\u001f" + filter.cacheKey();
        List<BookId> cachedIds = searchCache.get(cacheKey);
        if (cachedIds != null && !cachedIds.isEmpty()) {
            log.debug("Пошук '{}' взято з кешу, знайдено {} книг", normalizedQuery, cachedIds.size());
            return loadBooks(cachedIds);
        }

        SearchRequest request = SearchRequest.builder()
                .text(normalizedQuery)
                .filterSpec(filter)
                .limit(limit)
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

    private SearchRequest withFilter(SearchRequest base, BookFilterSpec filter) {
        return SearchRequest.builder()
                .text(base.text()).authorId(base.authorId()).seriesId(base.seriesId()).genreId(base.genreId())
                .language(base.language()).ratingFrom(base.ratingFrom()).ratingTo(base.ratingTo())
                .yearFrom(base.yearFrom()).yearTo(base.yearTo()).addedFrom(base.addedFrom()).addedTo(base.addedTo())
                .localOnly(base.localOnly()).filterSpec(filter)
                .limit(base.limit()).offset(base.offset()).sortBy(base.sortBy()).direction(base.direction()).mode(base.mode())
                .build();
    }

    private List<BookDto> loadBooks(List<BookId> ids) {
        return bookQueryRepository.findByIds(ids).stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Універсальний пошук, який використовує DictionaryCache замість прямих запитів до БД.
     */
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
        String lowerQuery = normalizedQuery.toLowerCase(java.util.Locale.ROOT);

        // Автори шукаються bounded SQL-запитом: повний словник авторів не тримаємо в heap.
        List<AuthorDto> authors = authorRepository.searchByName(normalizedQuery, 20).stream()
                .map(authorMapper::toDto)
                .collect(Collectors.toList());
        results.put("authors", authors);

        // Пошук серій з кешу
        List<String> series = dictionaryCache.getAllSeriesNames().stream()
                .filter(name -> name != null && name.toLowerCase().contains(lowerQuery))
                .limit(20)
                .collect(Collectors.toList());
        results.put("series", series);

        // Пошук жанрів з кешу
        List<GenreDto> genres = dictionaryCache.getAllGenres().stream()
                .filter(genre -> genre.getName() != null && genre.getName().toLowerCase().contains(lowerQuery))
                .map(genreMapper::toDto)
                .limit(20)
                .collect(Collectors.toList());
        results.put("genres", genres);

        // Пошук книг через Lucene
        List<BookDto> books = search(normalizedQuery, 50);
        results.put("books", books);

        log.debug("Пошук '{}' завершено: авторів {}, серій {}, жанрів {}, книг {}",
                normalizedQuery, authors.size(), series.size(), genres.size(), books.size());
        return results;
    }
}