package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.domain.model.reader.ReaderPreferences;

import java.util.Objects;

public record ReaderSettingsState(ReaderPreferences preferences, Source source) {
    public enum Source { GLOBAL, FORMAT, SERIES, BOOK }

    public ReaderSettingsState {
        preferences = Objects.requireNonNull(preferences, "preferences");
        source = source == null ? Source.GLOBAL : source;
    }

    /** Compatibility with the V27 UI/tests. */
    public boolean bookOverride() {
        return source == Source.BOOK;
    }

    public boolean scopedOverride() {
        return source != Source.GLOBAL;
    }
}
