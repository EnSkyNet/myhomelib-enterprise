package com.myhomelibcorp.application.reader;

import static com.myhomelibcorp.application.operation.LibraryOperationLeaseSupport.withSharedLease;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.reader.ReaderBookPreferencesPort;
import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.application.port.out.reader.ReaderScopedPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

@Service
public class ReaderSettingsStateService {
    private final ReaderPreferencesPort globalPreferences;
    private final ReaderScopedPreferencesPort scopedPreferences;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated V27 tests/callers. */
    public ReaderSettingsStateService(ReaderPreferencesPort globalPreferences,
                                      ReaderBookPreferencesPort bookPreferences) {
        this(globalPreferences, bookOnlyAdapter(bookPreferences), null);
    }

    public ReaderSettingsStateService(ReaderPreferencesPort globalPreferences,
                                      ReaderScopedPreferencesPort scopedPreferences) {
        this(globalPreferences, scopedPreferences, null);
    }

    @Autowired
    public ReaderSettingsStateService(ReaderPreferencesPort globalPreferences,
                                      ReaderScopedPreferencesPort scopedPreferences,
                                      LibraryOperationCoordinator operationCoordinator) {
        this.globalPreferences = globalPreferences;
        this.scopedPreferences = scopedPreferences;
        this.operationCoordinator = operationCoordinator;
    }

    /** V27 compatibility: book override, otherwise global. */
    public ReaderSettingsState load(String bookId) {
        return load(bookId, null, null);
    }

    /** Resolves exact precedence: Book > Series > Format > Global. */
    public ReaderSettingsState load(String bookId, String series, String canonicalFormat) {
        return withReaderState(() -> {
            ReaderPreferenceScope book = ReaderPreferenceScope.tryBook(bookId);
            if (book != null) {
                Optional<ReaderPreferences> value = scopedPreferences.load(book);
                if (value.isPresent()) return new ReaderSettingsState(value.get(), ReaderSettingsState.Source.BOOK);
            }

            ReaderPreferenceScope seriesScope = ReaderPreferenceScope.trySeries(series);
            if (seriesScope != null) {
                Optional<ReaderPreferences> value = scopedPreferences.load(seriesScope);
                if (value.isPresent()) return new ReaderSettingsState(value.get(), ReaderSettingsState.Source.SERIES);
            }

            ReaderPreferenceScope formatScope = ReaderPreferenceScope.tryFormat(canonicalFormat);
            if (formatScope != null) {
                Optional<ReaderPreferences> value = scopedPreferences.load(formatScope);
                if (value.isPresent()) return new ReaderSettingsState(value.get(), ReaderSettingsState.Source.FORMAT);
            }
            return new ReaderSettingsState(globalPreferences.loadPreferences(), ReaderSettingsState.Source.GLOBAL);
        });
    }

    public ReaderPreferences loadGlobal() {
        return globalPreferences.loadPreferences();
    }

    public Optional<ReaderPreferences> loadScope(ReaderPreferenceScope scope) {
        if (scope == null) return Optional.empty();
        return withReaderState(() -> scopedPreferences.load(scope));
    }

    public void saveGlobal(ReaderPreferences preferences) {
        if (preferences != null) globalPreferences.savePreferences(preferences);
    }

    public void saveForBook(String bookId, ReaderPreferences preferences) {
        saveScope(ReaderPreferenceScope.tryBook(bookId), preferences);
    }

    public void saveForSeries(String series, ReaderPreferences preferences) {
        saveScope(ReaderPreferenceScope.trySeries(series), preferences);
    }

    public void saveForFormat(String format, ReaderPreferences preferences) {
        saveScope(ReaderPreferenceScope.tryFormat(format), preferences);
    }

    public void saveScope(ReaderPreferenceScope scope, ReaderPreferences preferences) {
        if (scope != null && preferences != null) {
            withReaderState(() -> scopedPreferences.save(scope, preferences));
        }
    }

    public void clearBookOverride(String bookId) {
        clearScope(ReaderPreferenceScope.tryBook(bookId));
    }

    public void clearSeriesOverride(String series) {
        clearScope(ReaderPreferenceScope.trySeries(series));
    }

    public void clearFormatOverride(String format) {
        clearScope(ReaderPreferenceScope.tryFormat(format));
    }

    public void clearScope(ReaderPreferenceScope scope) {
        if (scope != null) withReaderState(() -> scopedPreferences.delete(scope));
    }

    public void resetGlobal() {
        globalPreferences.resetPreferences();
    }

    private <T> T withReaderState(Supplier<T> action) {
        return withSharedLease(operationCoordinator, LibraryOperationType.READER_STATE, action);
    }

    private void withReaderState(Runnable action) {
        withReaderState(() -> {
            action.run();
            return null;
        });
    }

    private static ReaderScopedPreferencesPort bookOnlyAdapter(ReaderBookPreferencesPort port) {
        return new ReaderScopedPreferencesPort() {
            @Override public Optional<ReaderPreferences> load(ReaderPreferenceScope scope) {
                return scope != null && scope.type() == ReaderPreferenceScopeType.BOOK
                        ? port.load(scope.key()) : Optional.empty();
            }
            @Override public void save(ReaderPreferenceScope scope, ReaderPreferences preferences) {
                if (scope != null && scope.type() == ReaderPreferenceScopeType.BOOK) port.save(scope.key(), preferences);
            }
            @Override public void delete(ReaderPreferenceScope scope) {
                if (scope != null && scope.type() == ReaderPreferenceScopeType.BOOK) port.delete(scope.key());
            }
        };
    }
}
