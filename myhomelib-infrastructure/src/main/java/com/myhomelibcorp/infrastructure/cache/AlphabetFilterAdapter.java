package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.AlphabetFilterPort;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.series.Series;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
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

    @Override
    public Collection<Author> getAuthorsByLetter(char letter) {
        Collection<Author> allAuthors = dictionaryCache.getAllAuthors();
        if (allAuthors == null || allAuthors.isEmpty()) {
            log.debug("AlphabetFilterAdapter: no authors to filter");
            return List.of();
        }

        if (letter == '*') {
            log.debug("AlphabetFilterAdapter: returning all {} authors", allAuthors.size());
            return allAuthors;
        }

        Collection<Author> filtered = allAuthors.stream()
                .filter(author -> {
                    String lastName = author.getLastName();
                    if (lastName == null || lastName.isEmpty()) {
                        return letter == '#';
                    }
                    char first = Character.toUpperCase(lastName.charAt(0));
                    if (letter == '#') {
                        return !Character.isLetter(first);
                    }
                    return first == letter;
                })
                .collect(Collectors.toList());

        log.debug("AlphabetFilterAdapter: filtered {} authors by letter '{}' (was {})",
                filtered.size(), letter, allAuthors.size());
        return filtered;
    }

    @Override
    public Collection<Series> getSeriesByLetter(char letter) {
        Collection<Series> allSeries = dictionaryCache.getAllSeries();
        if (allSeries == null || allSeries.isEmpty()) {
            log.debug("AlphabetFilterAdapter: no series to filter");
            return List.of();
        }

        if (letter == '*') {
            log.debug("AlphabetFilterAdapter: returning all {} series", allSeries.size());
            return allSeries;
        }

        Collection<Series> filtered = allSeries.stream()
                .filter(series -> {
                    String name = series.getName();
                    if (name == null || name.isEmpty()) {
                        return letter == '#';
                    }
                    char first = Character.toUpperCase(name.charAt(0));
                    if (letter == '#') {
                        return !Character.isLetter(first);
                    }
                    return first == letter;
                })
                .collect(Collectors.toList());

        log.debug("AlphabetFilterAdapter: filtered {} series by letter '{}' (was {})",
                filtered.size(), letter, allSeries.size());
        return filtered;
    }
}