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

    List<Author> findByInitial(char initial);
    Optional<Character> findFirstInitial();
    long countByInitial(char initial);
    List<Author> searchByName(String query, int limit);

    long countOrphanedAuthors();
}
