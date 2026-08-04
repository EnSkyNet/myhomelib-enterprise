package com.myhomelibcorp.application.usecase.navigation;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.dto.NavigationDataDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.GenreMapper;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LoadNavigationDataUseCase {

    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;
    private final AuthorMapper authorMapper;
    private final GenreMapper genreMapper;
    private final ExecutorPort executorPort;

    public CompletableFuture<NavigationDataDto> execute() {
        return executorPort.submit(() -> {
            List<AuthorDto> authors = authorRepository.findAll().stream()
                    .map(authorMapper::toDto)
                    .collect(Collectors.toList());

            List<GenreDto> genres = genreRepository.findAll().stream()
                    .map(genreMapper::toDto)
                    .collect(Collectors.toList());

            List<String> seriesNames = seriesRepository.getAllSeriesNames();

            return NavigationDataDto.builder()
                    .authors(authors)
                    .genres(genres)
                    .seriesNames(seriesNames)
                    .build();
        });
    }
}