package com.myhomelibcorp.application.navigation;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.GenreMapper;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NavigationService {

    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;
    private final CollectionRepository collectionRepository;
    private final AuthorMapper authorMapper;
    private final GenreMapper genreMapper;

    public CompletableFuture<List<AuthorDto>> getAllAuthors() {
        return CompletableFuture.supplyAsync(() ->
                authorRepository.findAll().stream()
                        .map(authorMapper::toDto)
                        .collect(Collectors.toList())
        );
    }

    public CompletableFuture<List<String>> getAllSeriesNames() {
        return CompletableFuture.supplyAsync(seriesRepository::getAllSeriesNames);
    }

    public CompletableFuture<List<GenreDto>> getAllGenres() {
        return CompletableFuture.supplyAsync(() ->
                genreRepository.findAll().stream()
                        .map(genreMapper::toDto)
                        .collect(Collectors.toList())
        );
    }

    public CompletableFuture<List<AuthorDto>> getAuthorsByLetter(char letter) {
        return CompletableFuture.supplyAsync(() ->
                authorRepository.findAll().stream()
                        .filter(author -> {
                            String lastName = author.getLastName();
                            return lastName != null && !lastName.isEmpty()
                                    && Character.toUpperCase(lastName.charAt(0)) == Character.toUpperCase(letter);
                        })
                        .map(authorMapper::toDto)
                        .collect(Collectors.toList())
        );
    }

    public CompletableFuture<List<AuthorDto>> searchAuthors(String query) {
        return CompletableFuture.supplyAsync(() ->
                authorRepository.findAll().stream()
                        .filter(author -> {
                            String fullName = author.getFullName().toLowerCase();
                            return fullName.contains(query.toLowerCase());
                        })
                        .map(authorMapper::toDto)
                        .limit(20)
                        .collect(Collectors.toList())
        );
    }
}