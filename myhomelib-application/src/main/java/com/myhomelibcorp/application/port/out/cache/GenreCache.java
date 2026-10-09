package com.myhomelibcorp.application.port.out.cache;

import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;

public interface GenreCache extends EntityCache<GenreId, Genre> {
}