package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;
import java.util.regex.Pattern;

public record Isbn(String value) {
    private static final Pattern ISBN13_PATTERN = Pattern.compile("^(978|979)\\d{10}$");
    private static final Pattern ISBN10_PATTERN = Pattern.compile("^\\d{9}[\\dXx]$");

    public Isbn {
        Objects.requireNonNull(value, "ISBN cannot be null");
        String normalized = value.replaceAll("[\\s-]", "");
        if (!isValid(normalized)) {
            throw new IllegalArgumentException("Invalid ISBN format: " + value);
        }
        value = normalized;
    }

    public static Isbn of(String value) {
        return new Isbn(value);
    }

    private static boolean isValid(String isbn) {
        return ISBN13_PATTERN.matcher(isbn).matches()
                || ISBN10_PATTERN.matcher(isbn).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}