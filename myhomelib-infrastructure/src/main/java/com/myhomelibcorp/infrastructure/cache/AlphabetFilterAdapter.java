package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.AlphabetFilterPort;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.series.Series;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Adapter for filtering dictionaries by alphabet.
 * Uses DictionaryCache to get data.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlphabetFilterAdapter implements AlphabetFilterPort {

    private final DictionaryCache dictionaryCache;
    private final AuthorRepository authorRepository;

    @Override
    public Collection<Author> getAuthorsByLetter(char letter) {
        char normalizedLetter = normalizeLetter(letter);
        if (normalizedLetter == '*') {
            return authorRepository.findFirstInitial()
                    .map(authorRepository::findByInitial)
                    .orElseGet(List::of);
        }
        return authorRepository.findByInitial(normalizedLetter);
    }

    @Override
    public Collection<Series> getSeriesByLetter(char letter) {
        char normalizedLetter = normalizeLetter(letter);
        Collection<Series> allSeries = dictionaryCache.getAllSeries();
        if (allSeries == null || allSeries.isEmpty()) {
            log.debug("AlphabetFilterAdapter: no series to filter");
            return List.of();
        }

        if (normalizedLetter == '*') {
            log.debug("AlphabetFilterAdapter: returning all {} series", allSeries.size());
            return allSeries;
        }

        Collection<Series> filtered = allSeries.stream()
                .filter(series -> {
                    String name = series.getName();
                    if (name == null || name.isEmpty()) {
                        return normalizedLetter == '#';
                    }
                    char first = Character.toUpperCase(name.charAt(0));
                    if (normalizedLetter == '#') {
                        return !Character.isLetter(first);
                    }
                    return first == normalizedLetter;
                })
                .collect(Collectors.toList());

        log.debug("AlphabetFilterAdapter: filtered {} series by letter '{}' (was {})",
                filtered.size(), normalizedLetter, allSeries.size());
        return filtered;
    }

    /**
     * Нормалізує літеру для фільтрації: переводить у верхній регістр.
     */
    private char normalizeLetter(char letter) {
        if (letter == '*' || letter == '#') {
            return letter;
        }
        return Character.toUpperCase(letter);
    }
}