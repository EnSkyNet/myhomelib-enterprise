package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.reader.ReaderBookPreferencesPort;
import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
public class ReaderSettingsStateService {
    private final ReaderPreferencesPort globalPreferences;
    private final ReaderBookPreferencesPort bookPreferences;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/callers without lifecycle wiring. */
    public ReaderSettingsStateService(
            ReaderPreferencesPort globalPreferences,
            ReaderBookPreferencesPort bookPreferences) {
        this(globalPreferences, bookPreferences, null);
    }

    @Autowired
    public ReaderSettingsStateService(
            ReaderPreferencesPort globalPreferences,
            ReaderBookPreferencesPort bookPreferences,
            LibraryOperationCoordinator operationCoordinator) {
        this.globalPreferences = globalPreferences;
        this.bookPreferences = bookPreferences;
        this.operationCoordinator = operationCoordinator;
    }

    public ReaderSettingsState load(String bookId) {
        if (bookId != null && !bookId.isBlank()) {
            var override = withReaderState(() -> bookPreferences.load(bookId));
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
            withReaderState(() -> bookPreferences.save(bookId, preferences));
        }
    }

    public void clearBookOverride(String bookId) {
        if (bookId != null && !bookId.isBlank()) {
            withReaderState(() -> bookPreferences.delete(bookId));
        }
    }

    public void resetGlobal() {
        globalPreferences.resetPreferences();
    }

    private <T> T withReaderState(Supplier<T> action) {
        if (operationCoordinator == null) return action.get();
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.READER_STATE);
        try {
            T result = action.get();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private void withReaderState(Runnable action) {
        withReaderState(() -> {
            action.run();
            return null;
        });
    }
}
