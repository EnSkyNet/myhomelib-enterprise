package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class GlobalCache {

    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;

    private final Map<AuthorId, Author> authorCache = new ConcurrentHashMap<>();
    private final Map<String, AuthorId> authorNameCache = new ConcurrentHashMap<>();
    private final Map<GenreId, Genre> genreCache = new ConcurrentHashMap<>();
    private final Map<String, GenreId> genreCodeCache = new ConcurrentHashMap<>();
    private final Map<SeriesId, Series> seriesCache = new ConcurrentHashMap<>();
    private final Map<String, SeriesId> seriesNameCache = new ConcurrentHashMap<>();

    private boolean initialized = false;

    /**
     * Явний метод ініціалізації – викликати після вибору колекції.
     */
    public void initialize() {
        if (initialized) {
            log.debug("GlobalCache вже ініціалізовано");
            return;
        }
        log.info("Ініціалізація GlobalCache...");
        loadAuthors();
        loadGenres();
        loadSeries();
        initialized = true;
        log.info("GlobalCache ініціалізовано: {} авторів, {} жанрів, {} серій",
                authorCache.size(), genreCache.size(), seriesCache.size());
    }

    private void loadAuthors() {
        try {
            List<Author> authors = authorRepository.findAll();
            authorCache.clear();
            authorNameCache.clear();
            for (Author a : authors) {
                authorCache.put(a.getId(), a);
                String key = buildAuthorKey(a);
                authorNameCache.put(key, a.getId());
            }
            log.debug("Завантажено {} авторів у GlobalCache", authorCache.size());
        } catch (Exception e) {
            log.error("Помилка завантаження авторів у GlobalCache", e);
        }
    }

    private void loadGenres() {
        try {
            List<Genre> genres = genreRepository.findAll();
            genreCache.clear();
            genreCodeCache.clear();
            for (Genre g : genres) {
                genreCache.put(g.getId(), g);
                genreCodeCache.put(g.getId().asString(), g.getId());
            }
            log.debug("Завантажено {} жанрів у GlobalCache", genreCache.size());
        } catch (Exception e) {
            log.error("Помилка завантаження жанрів у GlobalCache", e);
        }
    }

    private void loadSeries() {
        try {
            List<Series> seriesList = seriesRepository.findAll();
            seriesCache.clear();
            seriesNameCache.clear();
            for (Series s : seriesList) {
                seriesCache.put(s.getId(), s);
                if (s.getName() != null) {
                    seriesNameCache.put(s.getName().toLowerCase(), s.getId());
                }
            }
            log.debug("Завантажено {} серій у GlobalCache", seriesCache.size());
        } catch (Exception e) {
            log.error("Помилка завантаження серій у GlobalCache", e);
        }
    }

    private String buildAuthorKey(Author a) {
        return (a.getFirstName() != null ? a.getFirstName() : "") + "|" +
                (a.getMiddleName() != null ? a.getMiddleName() : "") + "|" +
                (a.getLastName() != null ? a.getLastName() : "");
    }

    // ---- Методи доступу до кешу ----

    public Optional<Author> getAuthor(AuthorId id) {
        return Optional.ofNullable(authorCache.get(id));
    }

    public Optional<AuthorId> getAuthorId(String firstName, String lastName) {
        return getAuthorId(firstName, "", lastName);
    }

    public Optional<AuthorId> getAuthorId(String firstName, String middleName, String lastName) {
        String key = (firstName != null ? firstName : "") + "|" +
                (middleName != null ? middleName : "") + "|" +
                (lastName != null ? lastName : "");
        return Optional.ofNullable(authorNameCache.get(key));
    }

    public Optional<Genre> getGenre(GenreId id) {
        return Optional.ofNullable(genreCache.get(id));
    }

    public Optional<GenreId> getGenreId(String code) {
        return Optional.ofNullable(genreCodeCache.get(code));
    }

    public Optional<Series> getSeries(SeriesId id) {
        return Optional.ofNullable(seriesCache.get(id));
    }

    public Optional<SeriesId> getSeriesId(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(seriesNameCache.get(name.toLowerCase()));
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Оновлює кеш після імпорту або змін у базі даних.
     */
    // Додати метод:
    /**
     * Оновлює кеш (перезавантажує з БД).
     */
    public void refresh() {
        initialized = false;
        initialize();
        log.info("GlobalCache оновлено");
    }

    /**
     * Очищує весь кеш.
     */
    public void clear() {
        authorCache.clear();
        authorNameCache.clear();
        genreCache.clear();
        genreCodeCache.clear();
        seriesCache.clear();
        seriesNameCache.clear();
        initialized = false;
        log.info("GlobalCache очищено");
    }


}