package com.myhomelibcorp.application.genre;

import com.myhomelibcorp.application.port.out.GenreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LoadGenresUseCase {

    private final GenreService genreService;

    public List<String> getAllGenreNames() {
        return genreService.getAllGenreNames();
    }

    public Map<String, String> getAllGenres() {
        return genreService.getAllGenres();
    }
}