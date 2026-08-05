package com.myhomelibcorp.application.port.out.reader;

import com.myhomelibcorp.domain.model.reader.ReaderPreferences;

public interface ReaderPreferencesPort {

    ReaderPreferences loadPreferences();

    void savePreferences(ReaderPreferences preferences);

    void resetPreferences();
}