package com.myhomelibcorp.application.filter;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.query.book.BookFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Persisted global Stage 8 filter state. */
@Service
@RequiredArgsConstructor
public class BookFilterStateService {
    private static final String P = "filter.global.";
    private final ApplicationSettingsPort settings;

    public BookFilterSpec current() {
        return new BookFilterSpec(
                enumValue(BookFilterMode.class, settings.get(P + "mode", BookFilterMode.AND.name()), BookFilterMode.AND),
                blankToNull(settings.get(P + "language", "")),
                integer(P + "yearFrom"),
                integer(P + "yearTo"),
                enumNullable(BookFormat.class, settings.get(P + "format", "")),
                boolNullable(P + "local"),
                boolNullable(P + "read"),
                integer(P + "ratingMin"),
                integer(P + "ratingMax"),
                settings.getBoolean(P + "hideUnrated", false),
                enumValue(BookQuickFilterField.class, settings.get(P + "quickField", BookQuickFilterField.ANY.name()), BookQuickFilterField.ANY),
                blankToNull(settings.get(P + "quickValue", ""))
        );
    }

    public void save(BookFilterSpec spec) {
        BookFilterSpec value = spec == null ? BookFilterSpec.empty() : spec;
        put(P + "mode", value.mode().name());
        putNullable(P + "language", value.language());
        putNullable(P + "yearFrom", value.yearFrom());
        putNullable(P + "yearTo", value.yearTo());
        putNullable(P + "format", value.format() == null ? null : value.format().name());
        putNullable(P + "local", value.local());
        putNullable(P + "read", value.read());
        putNullable(P + "ratingMin", value.ratingMin());
        putNullable(P + "ratingMax", value.ratingMax());
        settings.putBoolean(P + "hideUnrated", value.hideUnrated());
        put(P + "quickField", value.quickField().name());
        putNullable(P + "quickValue", value.quickValue());
    }

    public void reset() { save(BookFilterSpec.empty()); }

    private Integer integer(String key) {
        String value = settings.get(key, "");
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value.trim()); } catch (NumberFormatException ignored) { return null; }
    }
    private Boolean boolNullable(String key) {
        String value = settings.get(key, "");
        if (value == null || value.isBlank()) return null;
        return Boolean.valueOf(value);
    }
    private void put(String key, String value) { settings.put(key, value == null ? "" : value); }
    private void putNullable(String key, Object value) {
        if (value == null) settings.remove(key); else settings.put(key, value.toString());
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value); } catch (Exception ignored) { return fallback; }
    }
    private static <E extends Enum<E>> E enumNullable(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try { return Enum.valueOf(type, value); } catch (Exception ignored) { return null; }
    }
}
