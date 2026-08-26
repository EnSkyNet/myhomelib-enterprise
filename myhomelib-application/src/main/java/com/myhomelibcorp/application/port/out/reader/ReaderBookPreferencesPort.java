package com.myhomelibcorp.application.port.out.reader;

import com.myhomelibcorp.domain.model.reader.ReaderPreferences;

import java.util.Optional;

/** Per-book Reader preference overrides. */
public interface ReaderBookPreferencesPort {
    Optional<ReaderPreferences> load(String bookId);
    void save(String bookId, ReaderPreferences preferences);
    void delete(String bookId);
}
