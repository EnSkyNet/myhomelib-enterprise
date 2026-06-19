package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;
import java.util.regex.Pattern;

public record LanguageCode(String value) {
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");

    public LanguageCode {
        Objects.requireNonNull(value, "Language code cannot be null");
        String normalized = value.trim().toLowerCase();
        if (!isValid(normalized)) {
            throw new IllegalArgumentException("Invalid language code: " + value);
        }
        value = normalized;
    }

    public static LanguageCode of(String value) {
        return new LanguageCode(value);
    }

    private static boolean isValid(String code) {
        // Перевірка лише за регулярним виразом – швидше і надійніше
        return LANGUAGE_PATTERN.matcher(code).matches();
    }

    public String displayName() {
        // Спроба отримати назву мови, якщо не вдається – повертаємо код
        try {
            return new java.util.Locale(value).getDisplayLanguage();
        } catch (Exception e) {
            return value;
        }
    }

    @Override
    public String toString() {
        return value;
    }
}