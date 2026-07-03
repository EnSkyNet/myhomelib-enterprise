package com.myhomelibcorp.application.port.out.cache;

import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;

import java.util.Optional;

public interface GenreCache {
    Optional<Genre> get(GenreId id);
    void put(GenreId id, Genre genre);
    void evict(GenreId id);
    void clear();
}