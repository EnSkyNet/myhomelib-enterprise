package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface GenreRepository {
    List<Genre> findAll();
    Optional<Genre> findById(GenreId id);
    Genre save(Genre genre);
    void deleteById(GenreId id);

    String getGenreName(String code);
    List<String> getAllGenreNames();
    Map<String, String> getAllGenres();
    List<String> getAllGenreCodes();
    List<Genre> getAllGenresHierarchy();
    List<Genre> searchByName(String query, int limit);

    // ----- НОВИЙ МЕТОД ДЛЯ DATA INTEGRITY -----

    /**
     * Повертає кількість жанрів, які не прив'язані до жодної книги.
     */
    long countOrphanedGenres();
}