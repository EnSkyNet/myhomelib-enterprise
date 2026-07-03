package com.myhomelibcorp.application.usecase.genre;

import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.genre.Genre;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LoadGenresUseCase {

    private final GenreRepository genreRepository;

    public List<String> getAllGenreNames() {
        return genreRepository.getAllGenreNames();
    }

    public Map<String, String> getAllGenres() {
        return genreRepository.getAllGenres();
    }

    public List<Genre> getAllGenresHierarchy() {
        return genreRepository.getAllGenresHierarchy();
    }
}