package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.port.out.cache.AlphabetFilterPort;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.series.Series;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for filtering navigation items by alphabet.
 * Uses AlphabetFilterPort for data access.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NavigationFilterService {

    private final AlphabetFilterPort alphabetFilterPort;

    /**
     * Get authors filtered by first letter.
     * @param letter filter letter ('*' for all, '#' for non-letters)
     * @return sorted list of authors
     */
    public List<Author> getAuthorsByLetter(char letter) {
        Collection<Author> authors = alphabetFilterPort.getAuthorsByLetter(letter);
        if (authors == null || authors.isEmpty()) {
            log.debug("getAuthorsByLetter: no authors for letter '{}'", letter);
            return List.of();
        }

        return authors.stream()
                .sorted(Comparator.comparing(Author::getLastName))
                .collect(Collectors.toList());
    }

    /**
     * Get series filtered by first letter.
     * @param letter filter letter ('*' for all, '#' for non-letters)
     * @return sorted list of series
     */
    public List<Series> getSeriesByLetter(char letter) {
        Collection<Series> series = alphabetFilterPort.getSeriesByLetter(letter);
        if (series == null || series.isEmpty()) {
            log.debug("getSeriesByLetter: no series for letter '{}'", letter);
            return List.of();
        }

        return series.stream()
                .sorted(Comparator.comparing(Series::getName))
                .collect(Collectors.toList());
    }

    /**
     * Check if there are any authors for the given letter.
     */
    public boolean hasAuthorsForLetter(char letter) {
        return !alphabetFilterPort.getAuthorsByLetter(letter).isEmpty();
    }

    /**
     * Check if there are any series for the given letter.
     */
    public boolean hasSeriesForLetter(char letter) {
        return !alphabetFilterPort.getSeriesByLetter(letter).isEmpty();
    }

    /**
     * Get display label for empty state.
     */
    public String getEmptyMessage(char letter, boolean isAuthorMode) {
        String type = isAuthorMode ? "authors" : "series";
        if (letter == '*') {
            return "No " + type + " found";
        }
        return "No " + type + " starting with '" + letter + "'";
    }

    /**
     * Get the first letter from a string for filtering.
     */
    public char getFirstLetter(String text) {
        if (text == null || text.isEmpty()) {
            return '#';
        }
        char first = text.charAt(0);
        if (Character.isLetter(first)) {
            return Character.toUpperCase(first);
        }
        return '#';
    }
}