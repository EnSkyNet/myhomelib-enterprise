package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import java.util.Locale;
import java.util.stream.Stream;

/** Canonical Unicode-aware author search key stored in SQLite. */
public final class AuthorSearchNameNormalizer {
    private AuthorSearchNameNormalizer() {}

    public static String normalize(String firstName, String middleName, String lastName) {
        return Stream.of(lastName, firstName, middleName)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .reduce((left, right) -> left + " " + right)
                .orElse("")
                .toLowerCase(Locale.ROOT);
    }
}
