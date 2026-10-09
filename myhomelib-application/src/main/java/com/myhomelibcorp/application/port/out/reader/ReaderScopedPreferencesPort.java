package com.myhomelibcorp.application.port.out.reader;

import com.myhomelibcorp.application.reader.ReaderPreferenceScope;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;

import java.util.Optional;

/** Collection-scoped Reader preference overrides for book/series/format scopes. */
public interface ReaderScopedPreferencesPort {
    Optional<ReaderPreferences> load(ReaderPreferenceScope scope);
    void save(ReaderPreferenceScope scope, ReaderPreferences preferences);
    void delete(ReaderPreferenceScope scope);
}
