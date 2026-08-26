package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.domain.model.reader.ReaderPreferences;

public record ReaderSettingsState(ReaderPreferences preferences, boolean bookOverride) {
}
