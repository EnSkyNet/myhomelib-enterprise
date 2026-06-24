package com.myhomelibcorp.application.port.out;

import com.myhomelibcorp.domain.model.genre.Genre;

import java.util.List;
import java.util.Map;

public interface GenreService {
    String getGenreName(String code);
    List<String> getAllGenreNames();
    Map<String, String> getAllGenres();
    List<String> getAllGenreCodes();

    // +++ НОВИЙ МЕТОД +++
    List<Genre> getAllGenresHierarchy();
}