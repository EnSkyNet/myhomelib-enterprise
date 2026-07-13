package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.mapper.GenreMapper;
import com.myhomelibcorp.application.port.out.cache.SearchCache;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
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

/**
 * Сервіс пошуку, який об'єднує пошук за книгами, авторами, серіями та жанрами.
 * Використовує Lucene для пошуку книг та прямі запити до репозиторіїв для інших сутностей.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final SearchQueryService searchQueryService;
    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final SearchCache searchCache;
    private final AuthorRepository authorRepository;
    private final SeriesRepository seriesRepository;
    private final GenreRepository genreRepository;
    private final AuthorMapper authorMapper;
    private final GenreMapper genreMapper;

    /**
     * Пошук книг за текстовим запитом через Lucene з кешуванням.
     *
     * @param query текст пошуку
     * @param limit максимальна кількість результатів
     * @return список знайдених книг
     */
    public List<BookDto> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // Перевіряємо кеш
        List<BookId> cachedIds = searchCache.get(query);
        if (cachedIds != null && !cachedIds.isEmpty()) {
            log.debug("Пошук '{}' взято з кешу, знайдено {} книг", query, cachedIds.size());
            return loadBooks(cachedIds);
        }

        SearchRequest request = SearchRequest.builder()
                .text(query)
                .limit(limit)
                .build();
        SearchResult result = searchQueryService.search(request);
        if (result.isEmpty()) {
            log.debug("Пошук '{}' не знайшов результатів", query);
            return List.of();
        }

        List<BookId> ids = result.bookIds();
        searchCache.put(query, ids);
        log.debug("Пошук '{}' знайшов {} книг, збережено в кеш", query, ids.size());
        return loadBooks(ids);
    }

    /**
     * Завантажує повні дані книг за їх ID.
     */
    private List<BookDto> loadBooks(List<BookId> ids) {
        return bookQueryRepository.findByIds(ids).stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Універсальний пошук, який повертає результати у вигляді карти з категоріями:
     * <ul>
     *   <li>authors – список {@link AuthorDto}</li>
     *   <li>series – список назв серій {@link String}</li>
     *   <li>genres – список {@link GenreDto}</li>
     *   <li>books – список {@link BookDto}</li>
     * </ul>
     *
     * @param query текст пошуку
     * @return карта з результатами пошуку за категоріями
     */
    public Map<String, Object> searchAll(String query) {
        if (query == null || query.isBlank()) {
            return Map.of(
                    "authors", List.of(),
                    "series", List.of(),
                    "genres", List.of(),
                    "books", List.of()
            );
        }

        Map<String, Object> results = new HashMap<>();
        String lowerQuery = query.toLowerCase();

        // Пошук авторів
        List<AuthorDto> authors = authorRepository.findAll().stream()
                .filter(author -> author.getFullName().toLowerCase().contains(lowerQuery))
                .map(authorMapper::toDto)
                .limit(20)
                .collect(Collectors.toList());
        results.put("authors", authors);

        // Пошук серій
        List<String> series = seriesRepository.getAllSeriesNames().stream()
                .filter(name -> name != null && name.toLowerCase().contains(lowerQuery))
                .limit(20)
                .collect(Collectors.toList());
        results.put("series", series);

        // Пошук жанрів
        List<GenreDto> genres = genreRepository.findAll().stream()
                .filter(genre -> genre.getName() != null && genre.getName().toLowerCase().contains(lowerQuery))
                .map(genreMapper::toDto)
                .limit(20)
                .collect(Collectors.toList());
        results.put("genres", genres);

        // Пошук книг через Lucene
        List<BookDto> books = search(query, 50);
        results.put("books", books);

        log.debug("Пошук '{}' завершено: авторів {}, серій {}, жанрів {}, книг {}",
                query, authors.size(), series.size(), genres.size(), books.size());
        return results;
    }
}