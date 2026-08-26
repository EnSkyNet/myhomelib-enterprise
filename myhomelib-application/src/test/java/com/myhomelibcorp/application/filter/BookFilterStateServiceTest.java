package com.myhomelibcorp.application.filter;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.query.book.BookFormat;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookFilterStateServiceTest {

    @Test
    void persistsAndRestoresCompleteFilterState() {
        MapSettings settings = new MapSettings();
        BookFilterStateService service = new BookFilterStateService(settings);
        BookFilterSpec expected = new BookFilterSpec(
                BookFilterMode.OR, "UK", 2026, 2001, BookFormat.EPUB,
                true, false, 5, 2, true, BookQuickFilterField.AUTHOR, "Franko");

        service.save(expected);
        BookFilterSpec actual = service.current();

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.language()).isEqualTo("uk");
        assertThat(actual.yearFrom()).isEqualTo(2001);
        assertThat(actual.yearTo()).isEqualTo(2026);
        assertThat(actual.ratingMin()).isEqualTo(2);
        assertThat(actual.ratingMax()).isEqualTo(5);
    }

    @Test
    void resetRemovesActiveCriteria() {
        MapSettings settings = new MapSettings();
        BookFilterStateService service = new BookFilterStateService(settings);
        service.save(BookFilterSpec.empty().withQuickFilter(BookQuickFilterField.TITLE, "abc"));
        service.reset();
        assertThat(service.current().isActive()).isFalse();
    }

    private static final class MapSettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new HashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> out = new HashMap<>();
            values.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k, v); });
            return out;
        }
    }
}
