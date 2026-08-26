package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.application.port.out.reader.ReaderBookPreferencesPort;
import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderSettingsStateServiceTest {
    @Test
    void perBookOverrideWinsUntilExplicitlyCleared() {
        MemoryGlobal global = new MemoryGlobal();
        MemoryBooks books = new MemoryBooks();
        ReaderSettingsStateService service = new ReaderSettingsStateService(global, books);

        ReaderPreferences dark = ReaderPreferences.builder().theme("dark").build();
        service.saveForBook("b1", dark);
        assertThat(service.load("b1").bookOverride()).isTrue();
        assertThat(service.load("b1").preferences().getTheme()).isEqualTo("dark");

        service.clearBookOverride("b1");
        assertThat(service.load("b1").bookOverride()).isFalse();
        assertThat(service.load("b1").preferences().getTheme()).isEqualTo("light");
    }

    private static final class MemoryGlobal implements ReaderPreferencesPort {
        ReaderPreferences p = ReaderPreferences.builder().build();
        @Override public ReaderPreferences loadPreferences() { return p; }
        @Override public void savePreferences(ReaderPreferences preferences) { p = preferences; }
        @Override public void resetPreferences() { p = ReaderPreferences.builder().build(); }
    }

    private static final class MemoryBooks implements ReaderBookPreferencesPort {
        final Map<String, ReaderPreferences> m = new HashMap<>();
        @Override public Optional<ReaderPreferences> load(String bookId) { return Optional.ofNullable(m.get(bookId)); }
        @Override public void save(String bookId, ReaderPreferences preferences) { m.put(bookId, preferences); }
        @Override public void delete(String bookId) { m.remove(bookId); }
    }
}
