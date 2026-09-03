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

    /** Load a specific interactive page without leaking SearchRequest-copy logic into the UI layer. */
    public PageResult<BookDto> searchPage(SearchRequest request, int limit, int offset) {
        if (request == null) return PageResult.empty();
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return searchPage(withPaging(request, safeLimit, Math.max(0, offset), true));
    }

    /**
     * Continuation page for an already-counted interactive result set.
     * The first page supplies {@code knownTotal}; subsequent pages avoid repeating
     * an expensive Lucene count(query) while preserving the exact UI total.
     */
    public PageResult<BookDto> searchPage(SearchRequest request, int limit, int offset, long knownTotal) {
        if (request == null) return PageResult.empty();
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        int safeOffset = Math.max(0, offset);
        long safeTotal = Math.max(0, knownTotal);
        SearchRequest effective = withFilter(request,
                request.filterSpec() == null ? filterStateService.current() : request.filterSpec());
        SearchResult result = searchQueryService.search(withPaging(effective, safeLimit, safeOffset, false));
        List<BookDto> books = result.isEmpty() ? List.of() : loadBooks(result.bookIds());
        return PageResult.of(books, safeTotal, safeOffset / safeLimit, safeLimit);
    }

    /**
     * Bounded non-book overview for the interactive Search Workspace.
     *
     * <p>The previous UI path called {@link #searchAll(String)}, which also
     * materialized every matching book and every matching author.  For a
     * 700k–1M catalogue that defeats Lucene paging and can create hundreds of
     * thousands of Java objects before JavaFX renders the first row.  The
     * workspace now loads books separately through {@link #searchPage(SearchRequest)}
     * and keeps these secondary result groups intentionally bounded.</p>
     */
    public GlobalSearchResult searchOverview(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank()) {
            return GlobalSearchResult.empty();
        }

        List<AuthorDto> authors = searchAuthors(normalizedQuery, 200);
        List<String> series = seriesRepository.searchNames(normalizedQuery, 200);
        List<GenreDto> genres = genreRepository.searchByName(normalizedQuery, 200).stream()
                .map(genreMapper::toDto)
                .toList();
        return new GlobalSearchResult(authors, series, genres, List.of());
    }

    private SearchRequest withFilter(SearchRequest base, BookFilterSpec filter) {
        return SearchRequest.builder()
                .text(base.text()).authorId(base.authorId()).genreId(base.genreId())
                .language(base.language()).ratingFrom(base.ratingFrom()).ratingTo(base.ratingTo())
                .yearFrom(base.yearFrom()).yearTo(base.yearTo()).addedFrom(base.addedFrom()).addedTo(base.addedTo())
                .localOnly(base.localOnly()).filterSpec(filter)
                .limit(base.limit()).offset(base.offset()).mode(base.mode()).trackTotalHits(base.trackTotalHits())
                .build();
    }

    private List<BookDto> loadBooks(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) return List.of();

        // SQL IN (...) and the cache adapter do not guarantee input ordering.
        // Preserve Lucene relevance/deep-page order explicitly before mapping to UI DTOs.
        Map<BookId, BookDto> byId = new LinkedHashMap<>();
        for (var book : bookQueryRepository.findListItemsByIds(ids)) {
            if (book != null && book.getId() != null) byId.put(book.getId(), bookMapper.toDto(book));
        }
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * Global search for the workspace. Textual search no longer truncates books to 50
     * or authors to 20. Results are loaded through bounded server-side pages and then
     * exposed as one virtualized UI result set. Blank text with only a global filter
     * remains bounded to avoid accidentally materializing the complete collection.
     */
    public GlobalSearchResult searchAll(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        BookFilterSpec filter = filterStateService.current();
        if (normalizedQuery.isBlank()) {
            if (!filter.isActive()) return GlobalSearchResult.empty();
            return new GlobalSearchResult(List.of(), List.of(), List.of(), search("", 1000));
        }

        List<AuthorDto> authors = searchAuthorsAll(normalizedQuery);
        List<String> series = seriesRepository.searchNames(normalizedQuery, 200);
        List<GenreDto> genres = genreRepository.searchByName(normalizedQuery, 200).stream()
                .map(genreMapper::toDto)
                .toList();
        List<BookDto> books = searchAllBooks(normalizedQuery);

        log.debug("Пошук '{}' завершено: авторів {}, серій {}, жанрів {}, книг {}",
                normalizedQuery, authors.size(), series.size(), genres.size(), books.size());
        return new GlobalSearchResult(authors, series, genres, books);
    }

    /** Load every Lucene hit through bounded pages; no visible 50-book ceiling. */
    public List<BookDto> searchAllBooks(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        BookFilterSpec filter = filterStateService.current();
        if (normalizedQuery.isBlank() && !filter.isActive()) return List.of();
        SearchRequest base = SearchRequest.builder()
                .text(normalizedQuery)
                .filterSpec(filter)
                .mode(com.myhomelibcorp.application.query.search.SearchMode.PHRASE)
                .build();
        return searchAll(base);
    }

    /** Complete advanced-search result, fetched in bounded Lucene/SQL chunks. */
    public List<BookDto> searchAll(SearchRequest request) {
        if (request == null) return List.of();
        SearchRequest effective = withFilter(request,
                request.filterSpec() == null ? filterStateService.current() : request.filterSpec());
        final int chunkSize = 500;
        int offset = 0;
        long total = Long.MAX_VALUE;
        java.util.ArrayList<BookDto> all = new java.util.ArrayList<>();
        while (offset < total) {
            SearchRequest pageRequest = withPaging(effective, chunkSize, offset, offset == 0);
            SearchResult page = searchQueryService.search(pageRequest);
            if (offset == 0) total = page.totalHits();
            if (page.isEmpty()) break;
            List<BookDto> loaded = loadBooks(page.bookIds());
            if (loaded.isEmpty()) break;
            all.addAll(loaded);
            offset += page.bookIds().size();
            if (offset >= total || page.bookIds().size() < chunkSize) break;
        }
        return List.copyOf(all);
    }

    private SearchRequest withPaging(SearchRequest base, int limit, int offset) {
        return withPaging(base, limit, offset, base.trackTotalHits());
    }

    private SearchRequest withPaging(SearchRequest base, int limit, int offset, boolean trackTotalHits) {
        return SearchRequest.builder()
                .text(base.text()).authorId(base.authorId()).genreId(base.genreId())
                .language(base.language()).ratingFrom(base.ratingFrom()).ratingTo(base.ratingTo())
                .yearFrom(base.yearFrom()).yearTo(base.yearTo()).addedFrom(base.addedFrom()).addedTo(base.addedTo())
                .localOnly(base.localOnly()).filterSpec(base.filterSpec())
                .limit(limit).offset(offset).mode(base.mode()).trackTotalHits(trackTotalHits)
                .build();
    }

    /** All matching authors, loaded in bounded SQL pages rather than a hard 20-row cap. */
    public List<AuthorDto> searchAuthorsAll(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank()) return List.of();
        final int chunkSize = 500;
        int offset = 0;
        java.util.ArrayList<AuthorDto> all = new java.util.ArrayList<>();
        while (true) {
            List<com.myhomelibcorp.domain.model.author.Author> chunk =
                    authorRepository.searchByName(normalizedQuery, chunkSize, offset);
            if (chunk.isEmpty()) break;
            chunk.stream().map(authorMapper::toDto).forEach(all::add);
            offset += chunk.size();
            if (chunk.size() < chunkSize) break;
        }
        return List.copyOf(all);
    }

    /**
     * Bounded server-side author lookup used by the left navigation panel.
     * Keeping this operation separate prevents the UI from materializing or
     * filtering the complete author dictionary for large collections.
     */
    public List<AuthorDto> searchAuthors(String query, int limit) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank()) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return authorRepository.searchByName(normalizedQuery, safeLimit).stream()
                .map(authorMapper::toDto)
                .collect(Collectors.toList());
    }
}
