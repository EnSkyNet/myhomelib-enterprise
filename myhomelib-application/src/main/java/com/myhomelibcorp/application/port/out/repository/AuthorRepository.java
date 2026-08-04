package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;

import java.util.List;
import java.util.Optional;

public interface AuthorRepository {
    List<Author> findAll();
    Optional<Author> findById(AuthorId id);
    Author save(Author author);
    void deleteById(AuthorId id);
    Optional<Author> findByFullName(String firstName, String lastName);
    List<Author> findFavorites(int limit);

    // ----- НОВИЙ МЕТОД ДЛЯ DATA INTEGRITY -----

    /**
     * Повертає кількість авторів, які не прив'язані до жодної книги.
     */
    long countOrphanedAuthors();
}