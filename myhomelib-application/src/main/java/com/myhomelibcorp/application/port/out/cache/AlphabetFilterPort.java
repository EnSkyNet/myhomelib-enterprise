package com.myhomelibcorp.application.port.out.cache;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.series.Series;

import java.util.Collection;

/**
 * Port for filtering dictionaries by alphabet.
 * Used in alphabet navigation.
 */
public interface AlphabetFilterPort {

    /**
     * Returns authors whose last name starts with the specified letter.
     * @param letter filter letter ('*' - all, '#' - non-letters)
     * @return filtered collection of authors
     */
    Collection<Author> getAuthorsByLetter(char letter);

    /**
     * Returns series whose name starts with the specified letter.
     * @param letter filter letter ('*' - all, '#' - non-letters)
     * @return filtered collection of series
     */
    Collection<Series> getSeriesByLetter(char letter);

    /**
     * Checks if a character is a letter.
     */
    default boolean isLetter(char c) {
        return Character.isLetter(c);
    }

    /**
     * Checks if a character is special (non-letter).
     */
    default boolean isSpecial(char c) {
        return !isLetter(c) && c != '*' && c != '#';
    }

    /**
     * Gets the first letter for filtering from a string.
     * Returns '#' for non-letters.
     */
    default char getFirstLetter(String text) {
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