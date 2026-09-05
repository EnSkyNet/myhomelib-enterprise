package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Locale;

/** ISBN-10/ISBN-13 value object with checksum validation. */
public record Isbn(String value) {
    private static final Pattern ISBN13_PATTERN = Pattern.compile("^(978|979)\\d{10}$");
    private static final Pattern ISBN10_PATTERN = Pattern.compile("^\\d{9}[\\dX]$");

    public Isbn {
        Objects.requireNonNull(value, "ISBN cannot be null");
        String normalized = normalize(value);
        if (!isValid(normalized)) {
            throw new IllegalArgumentException("Invalid ISBN: " + value);
        }
        value = normalized;
    }

    public static Isbn of(String value) {
        return new Isbn(value);
    }

    /** Safe parser for persistence/import boundaries. */
    public static Optional<Isbn> tryParse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(new Isbn(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public static boolean isValidValue(String value) {
        if (value == null || value.isBlank()) return false;
        return isValid(normalize(value));
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static boolean isValid(String isbn) {
        if (ISBN13_PATTERN.matcher(isbn).matches()) return valid13(isbn);
        if (ISBN10_PATTERN.matcher(isbn).matches()) return valid10(isbn);
        return false;
    }

    private static boolean valid10(String isbn) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            int digit = (i == 9 && isbn.charAt(i) == 'X') ? 10 : isbn.charAt(i) - '0';
            sum += (10 - i) * digit;
        }
        return sum % 11 == 0;
    }

    private static boolean valid13(String isbn) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = isbn.charAt(i) - '0';
            sum += (i % 2 == 0 ? 1 : 3) * digit;
        }
        int check = (10 - (sum % 10)) % 10;
        return check == isbn.charAt(12) - '0';
    }

    @Override
    public String toString() {
        return value;
    }
}
