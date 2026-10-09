package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.application.port.out.reader.ReaderBookPreferencesPort;
import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.application.port.out.reader.ReaderScopedPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderSettingsStateServiceTest {
    @Test
    void exactPrecedenceIsBookThenSeriesThenFormatThenGlobalAndClearRevealsLowerScope() {
        MemoryGlobal global = new MemoryGlobal();
        MemoryScoped scoped = new MemoryScoped();
        ReaderSettingsStateService service = new ReaderSettingsStateService(global, scoped);

        service.saveGlobal(prefs("global"));
        service.saveForFormat("EPUB", prefs("format"));
        service.saveForSeries("  Series A  ", prefs("series"));
        service.saveForBook("b1", prefs("book"));

        assertState(service.load("b1", "series a", "epub"), "book", ReaderSettingsState.Source.BOOK);

        service.clearBookOverride("b1");
        assertState(service.load("b1", "Series A", "EPUB"), "series", ReaderSettingsState.Source.SERIES);

        service.clearSeriesOverride("SERIES A");
        assertState(service.load("b1", "Series A", "epub"), "format", ReaderSettingsState.Source.FORMAT);

        service.clearFormatOverride("EPUB");
        assertState(service.load("b1", "Series A", "epub"), "global", ReaderSettingsState.Source.GLOBAL);
    }

    @Test
    void seriesAndFormatScopesAreIsolatedAndBookOverrideRemainsHighest() {
        MemoryGlobal global = new MemoryGlobal();
        MemoryScoped scoped = new MemoryScoped();
        ReaderSettingsStateService service = new ReaderSettingsStateService(global, scoped);
        service.saveGlobal(prefs("global"));
        service.saveForFormat("epub", prefs("epub"));
        service.saveForFormat("pdf", prefs("pdf"));
        service.saveForSeries("Series A", prefs("series-a"));
        service.saveForSeries("Series B", prefs("series-b"));
        service.saveForBook("book-a", prefs("book-a"));

        assertState(service.load("book-a", "Series A", "EPUB"), "book-a", ReaderSettingsState.Source.BOOK);
        assertState(service.load("other-a", "series a", "epub"), "series-a", ReaderSettingsState.Source.SERIES);
        assertState(service.load("other-b", "Series B", "epub"), "series-b", ReaderSettingsState.Source.SERIES);
        assertState(service.load("other-c", null, "EPUB"), "epub", ReaderSettingsState.Source.FORMAT);
        assertState(service.load("other-d", null, "PDF"), "pdf", ReaderSettingsState.Source.FORMAT);
        assertState(service.load("other-e", null, "CBZ"), "global", ReaderSettingsState.Source.GLOBAL);
    }

    @Test
    void scopeIdentityNormalizesSeriesAndCanonicalFormatButNotBookId() {
        assertThat(ReaderPreferenceScope.series("  My Series ").key()).isEqualTo("my series");
        assertThat(ReaderPreferenceScope.format(" .EPUB ").key()).isEqualTo("epub");
        assertThat(ReaderPreferenceScope.book(" Book-ID ").key()).isEqualTo("Book-ID");
        assertThat(ReaderPreferenceScope.trySeries("  ")).isNull();
        assertThat(ReaderPreferenceScope.tryFormat("  ")).isNull();
    }

    @Test
    void legacyBookOnlyConstructorPreservesV27Behavior() {
        MemoryGlobal global = new MemoryGlobal();
        MemoryBooks books = new MemoryBooks();
        ReaderSettingsStateService service = new ReaderSettingsStateService(global, books);

        service.saveForBook("b1", prefs("dark"));
        assertThat(service.load("b1").bookOverride()).isTrue();
        assertThat(service.load("b1").preferences().getTheme()).isEqualTo("dark");

        service.clearBookOverride("b1");
        assertThat(service.load("b1").bookOverride()).isFalse();
        assertThat(service.load("b1").preferences().getTheme()).isEqualTo("light");
    }

    private static ReaderPreferences prefs(String theme) {
        return ReaderPreferences.builder().theme(theme).build();
    }

    private static void assertState(ReaderSettingsState state, String theme, ReaderSettingsState.Source source) {
        assertThat(state.preferences().getTheme()).isEqualTo(theme);
        assertThat(state.source()).isEqualTo(source);
    }

    private static final class MemoryGlobal implements ReaderPreferencesPort {
        ReaderPreferences p = ReaderPreferences.builder().build();
        @Override public ReaderPreferences loadPreferences() { return p; }
        @Override public void savePreferences(ReaderPreferences preferences) { p = preferences; }
        @Override public void resetPreferences() { p = ReaderPreferences.builder().build(); }
    }

    private static final class MemoryScoped implements ReaderScopedPreferencesPort {
        final Map<ReaderPreferenceScope, ReaderPreferences> values = new HashMap<>();
        @Override public Optional<ReaderPreferences> load(ReaderPreferenceScope scope) { return Optional.ofNullable(values.get(scope)); }
        @Override public void save(ReaderPreferenceScope scope, ReaderPreferences preferences) { values.put(scope, preferences); }
        @Override public void delete(ReaderPreferenceScope scope) { values.remove(scope); }
    }

    private static final class MemoryBooks implements ReaderBookPreferencesPort {
        final Map<String, ReaderPreferences> m = new HashMap<>();
        @Override public Optional<ReaderPreferences> load(String bookId) { return Optional.ofNullable(m.get(bookId)); }
        @Override public void save(String bookId, ReaderPreferences preferences) { m.put(bookId, preferences); }
        @Override public void delete(String bookId) { m.remove(bookId); }
    }
}
