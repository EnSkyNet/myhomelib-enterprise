package com.myhomelibcorp.application.port.out;

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
}