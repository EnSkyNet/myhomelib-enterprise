package com.myhomelibcorp.application.port.out.cache;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;

import java.util.Optional;

public interface AuthorCache {
    Optional<Author> get(AuthorId id);
    void put(AuthorId id, Author author);
    void evict(AuthorId id);
    void clear();
}