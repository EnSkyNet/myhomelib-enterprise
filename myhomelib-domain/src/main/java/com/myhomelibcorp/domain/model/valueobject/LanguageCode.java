package com.myhomelibcorp.domain.model.valueobject;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** A normalized BCP-47 language tag. */
public record LanguageCode(String value) {
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile(
            "^(?:[a-z]{2,3})(?:-[A-Za-z0-9]{2,8})*$");

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
        String source = value.trim().replace('_', '-');
        if (source.isEmpty()) return source;
        String[] parts = source.split("-", -1);
        if (parts.length == 0 || parts[0].isBlank()) return source;

        StringBuilder out = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank()) return source;
            out.append('-');
            if (part.length() == 4 && part.chars().allMatch(Character::isLetter)) {
                out.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1).toLowerCase(Locale.ROOT));
            } else if ((part.length() == 2 && part.chars().allMatch(Character::isLetter))
                    || (part.length() == 3 && part.chars().allMatch(Character::isDigit))) {
                out.append(part.toUpperCase(Locale.ROOT));
            } else {
                out.append(part.toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    public String displayName() {
        try {
            String display = Locale.forLanguageTag(value).getDisplayLanguage();
            return display == null || display.isBlank() ? value : display;
        } catch (Exception e) {
            return value;
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
