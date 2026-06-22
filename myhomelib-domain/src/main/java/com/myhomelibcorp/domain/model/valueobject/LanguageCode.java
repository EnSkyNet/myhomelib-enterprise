package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;
import java.util.Locale;
import java.util.regex.Pattern;

public record LanguageCode(String value) {
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");

    public LanguageCode {
        Objects.requireNonNull(value, "Language code cannot be null");
        String normalized = normalize(value);
        if (!isValid(normalized)) {
            throw new IllegalArgumentException("Invalid language code: " + value);
        }
        value = normalized;
    }

    public static LanguageCode of(String value) {
        return new LanguageCode(value);
    }

    private static boolean isValid(String code) {
        return LANGUAGE_PATTERN.matcher(code).matches();
    }

    private static String normalize(String value) {
        String normalized = value.trim().replace('_', '-');
        String[] parts = normalized.split("-", -1);
        if (parts.length == 1) {
            return parts[0].toLowerCase(Locale.ROOT);
        }
        if (parts.length == 2) {
            return parts[0].toLowerCase(Locale.ROOT) + "-" + parts[1].toUpperCase(Locale.ROOT);
        }
        return normalized;
    }

    public String displayName() {
        // Спроба отримати назву мови, якщо не вдається – повертаємо код
        try {
            return Locale.forLanguageTag(value).getDisplayLanguage();
        } catch (Exception e) {
            return value;
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
