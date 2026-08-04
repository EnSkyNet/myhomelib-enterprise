package com.myhomelibcorp.application.port.out.cache;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;

import java.util.Collection;
import java.util.Optional;

/**
 * Порт для доступу до кешованих словників (автори, жанри, серії, групи).
 * Реалізується в інфраструктурному шарі.
 */
public interface DictionaryCachePort {

    // ---- Завантаження ----
    void loadAuthors(Iterable<Author> authors);
    void loadGenres(Iterable<Genre> genres);
    void loadSeries(Iterable<Series> series);
    void loadGroups(Iterable<Group> groups);

    // ---- Геттери для пошуку (використовуються в SearchService) ----
    Collection<Author> getAllAuthors();
    Collection<Genre> getAllGenres();
    Collection<String> getAllSeriesNames();
    Collection<Series> getAllSeries(); // ДОДАНО

    // ---- Індивідуальні геттери ----
    Optional<Author> getAuthor(AuthorId id);
    Optional<AuthorId> getAuthorId(String firstName, String lastName, String middleName);
    Optional<Genre> getGenre(GenreId id);
    Optional<GenreId> getGenreId(String code);
    Optional<Series> getSeries(SeriesId id);
    Optional<SeriesId> getSeriesId(String name);
    Optional<Group> getGroup(Long id);
    Optional<Long> getGroupId(String name);

    // ---- Очищення ----
    void clearAll();
}