package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кеш словників, які рідко змінюються: автори, жанри, серії, групи.
 * Завантажується при старті та оновлюється після імпорту.
 */
@Component
@Slf4j
public class DictionaryCache {

    private final Map<AuthorId, Author> authorById = new ConcurrentHashMap<>();
    private final Map<String, AuthorId> authorByName = new ConcurrentHashMap<>();
    private final Map<GenreId, Genre> genreById = new ConcurrentHashMap<>();
    private final Map<String, GenreId> genreByCode = new ConcurrentHashMap<>();
    private final Map<SeriesId, Series> seriesById = new ConcurrentHashMap<>();
    private final Map<String, SeriesId> seriesByName = new ConcurrentHashMap<>();
    private final Map<Long, Group> groupById = new ConcurrentHashMap<>();
    private final Map<String, Long> groupByName = new ConcurrentHashMap<>();

    public void loadAuthors(Iterable<Author> authors) {
        authorById.clear();
        authorByName.clear();
        for (Author a : authors) {
            authorById.put(a.getId(), a);
            String key = buildAuthorKey(a);
            authorByName.put(key, a.getId());
        }
        log.info("Loaded {} authors into DictionaryCache", authorById.size());
    }

    public void loadGenres(Iterable<Genre> genres) {
        genreById.clear();
        genreByCode.clear();
        for (Genre g : genres) {
            genreById.put(g.getId(), g);
            genreByCode.put(g.getId().asString(), g.getId());
        }
        log.info("Loaded {} genres into DictionaryCache", genreById.size());
    }

    public void loadSeries(Iterable<Series> seriesList) {
        seriesById.clear();
        seriesByName.clear();
        for (Series s : seriesList) {
            seriesById.put(s.getId(), s);
            if (s.getName() != null) {
                seriesByName.put(s.getName().toLowerCase(), s.getId());
            }
        }
        log.info("Loaded {} series into DictionaryCache", seriesById.size());
    }

    public void loadGroups(Iterable<Group> groups) {
        groupById.clear();
        groupByName.clear();
        for (Group g : groups) {
            groupById.put(g.getId().asLong(), g);
            if (g.getName() != null) {
                groupByName.put(g.getName().toLowerCase(), g.getId().asLong());
            }
        }
        log.info("Loaded {} groups into DictionaryCache", groupById.size());
    }

    // ---- Геттери ----
    public Optional<Author> getAuthor(AuthorId id) {
        return Optional.ofNullable(authorById.get(id));
    }

    public Optional<AuthorId> getAuthorId(String firstName, String lastName, String middleName) {
        String key = buildAuthorKey(firstName, middleName, lastName);
        return Optional.ofNullable(authorByName.get(key));
    }

    public Optional<Genre> getGenre(GenreId id) {
        return Optional.ofNullable(genreById.get(id));
    }

    public Optional<GenreId> getGenreId(String code) {
        return Optional.ofNullable(genreByCode.get(code));
    }

    public Optional<Series> getSeries(SeriesId id) {
        return Optional.ofNullable(seriesById.get(id));
    }

    public Optional<SeriesId> getSeriesId(String name) {
        return Optional.ofNullable(seriesByName.get(name.toLowerCase()));
    }

    public Optional<Group> getGroup(Long id) {
        return Optional.ofNullable(groupById.get(id));
    }

    public Optional<Long> getGroupId(String name) {
        return Optional.ofNullable(groupByName.get(name.toLowerCase()));
    }

    private String buildAuthorKey(Author a) {
        return buildAuthorKey(a.getFirstName(), a.getMiddleName(), a.getLastName());
    }

    private String buildAuthorKey(String firstName, String middleName, String lastName) {
        return (firstName != null ? firstName : "") + "|" +
                (middleName != null ? middleName : "") + "|" +
                (lastName != null ? lastName : "");
    }

    /**
     * Очищує всі кеші словників.
     */
    public void clearAll() {
        authorById.clear();
        authorByName.clear();
        genreById.clear();
        genreByCode.clear();
        seriesById.clear();
        seriesByName.clear();
        groupById.clear();
        groupByName.clear();
        log.info("DictionaryCache очищено");
    }
}