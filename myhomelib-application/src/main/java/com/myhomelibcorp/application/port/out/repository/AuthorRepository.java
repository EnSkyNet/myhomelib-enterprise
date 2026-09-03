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

    /** Exact structured name lookup used only when the source has no external person identity. */
    default Optional<Author> findByName(String firstName, String middleName, String lastName) {
        return findByFullName(firstName, lastName)
                .filter(a -> java.util.Objects.equals(
                        a.getMiddleName() == null ? "" : a.getMiddleName(),
                        middleName == null ? "" : middleName));
    }

    /**
     * Heuristic identity lookup for local document scans where FB2/EPUB metadata may swap
     * first and last name fields. External/online catalogues must keep using source identity.
     */
    default Optional<Author> findEquivalentLocalName(String firstName, String middleName, String lastName) {
        Optional<Author> exact = findByName(firstName, middleName, lastName);
        if (exact.isPresent()) return exact;
        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) return Optional.empty();
        return findByName(lastName, middleName, firstName);
    }
    List<Author> findFavorites(int limit);

    List<Author> findByInitial(char initial);
    Optional<Character> findFirstInitial();
    long countByInitial(char initial);
    List<Author> searchByName(String query, int limit);

    /** Server-side page used by search workspaces that must not silently truncate to 20 authors. */
    default List<Author> searchByName(String query, int limit, int offset) {
        if (offset > 0) return List.of();
        return searchByName(query, limit);
    }

    long countOrphanedAuthors();
}
