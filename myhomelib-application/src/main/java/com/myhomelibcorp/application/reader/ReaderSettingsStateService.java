package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.application.port.out.reader.ReaderBookPreferencesPort;
import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReaderSettingsStateService {
    private final ReaderPreferencesPort globalPreferences;
    private final ReaderBookPreferencesPort bookPreferences;

    public ReaderSettingsState load(String bookId) {
        if (bookId != null && !bookId.isBlank()) {
            var override = bookPreferences.load(bookId);
            if (override.isPresent()) return new ReaderSettingsState(override.get(), true);
        }
        return new ReaderSettingsState(globalPreferences.loadPreferences(), false);
    }

    public ReaderPreferences loadGlobal() {
        return globalPreferences.loadPreferences();
    }

    public void saveGlobal(ReaderPreferences preferences) {
        if (preferences != null) globalPreferences.savePreferences(preferences);
    }

    public void saveForBook(String bookId, ReaderPreferences preferences) {
        if (bookId != null && !bookId.isBlank() && preferences != null) {
            bookPreferences.save(bookId, preferences);
        }
    }

    public void clearBookOverride(String bookId) {
        if (bookId != null && !bookId.isBlank()) bookPreferences.delete(bookId);
    }

    public void resetGlobal() {
        globalPreferences.resetPreferences();
    }
}
