package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.SessionRepository;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.prefs.Preferences;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteSessionRepository implements SessionRepository {

    private final QueryExecutor queryExecutor;
    private final SqliteBusyRetryExecutor busyRetry;
    private final Preferences prefs = Preferences.userNodeForPackage(SqliteSessionRepository.class);

    private static final String PREF_KEY_PREFIX = "lastOpenedBookId_";
    private static final String PREF_CLEARED_PREFIX = "lastOpenedBookCleared_";

    @Override
    public void saveLastOpenedBookId(String collectionId, String bookId) {
        if (collectionId == null || collectionId.isEmpty()) {
            log.warn("Спроба зберегти bookId без collectionId");
            return;
        }
        if (bookId == null || bookId.isEmpty()) {
            log.warn("Спроба зберегти порожній bookId");
            return;
        }

        String prefKey = PREF_KEY_PREFIX + collectionId;
        String clearedKey = PREF_CLEARED_PREFIX + collectionId;
        prefs.put(prefKey, bookId);
        prefs.remove(clearedKey);

        try {
            String sql = "INSERT OR REPLACE INTO session (key, value) VALUES (?, ?)";
            busyRetry.run("session last-opened write", () -> queryExecutor.update(sql, prefKey, bookId));
            log.debug("Збережено останню книгу для колекції {}: {}", collectionId, bookId);
        } catch (RuntimeException e) {
            // Preferences were already updated above, so a transient DB problem must not break Reader.
            if (!SqliteBusyRetryExecutor.isBusy(e)) {
                log.warn("Не вдалося зберегти lastOpenedBookId у БД: {}", e.getMessage());
            }
        }
    }

    @Override
    public String getLastOpenedBookId(String collectionId) {
        if (collectionId == null || collectionId.isEmpty()) {
            log.warn("Спроба отримати bookId без collectionId");
            return null;
        }

        String prefKey = PREF_KEY_PREFIX + collectionId;
        String clearedKey = PREF_CLEARED_PREFIX + collectionId;

        // Preferences are the authoritative local fallback because writes update them before
        // the best-effort SQLite mirror. Reading SQLite first can resurrect a stale value
        // after a transient DB write failure.
        String prefsValue = prefs.get(prefKey, null);
        if (prefsValue != null && !prefsValue.isEmpty()) {
            log.debug("Отримано останню книгу для колекції {} з Preferences: {}", collectionId, prefsValue);
            return prefsValue;
        }
        if (prefs.getBoolean(clearedKey, false)) {
            return null;
        }

        try {
            String sql = "SELECT value FROM session WHERE key = ?";
            String value = queryExecutor.queryForObject(sql, String.class, prefKey);
            if (value != null && !value.isEmpty()) {
                log.debug("Отримано останню книгу для колекції {} з БД: {}", collectionId, value);
                return value;
            }
        } catch (Exception e) {
            log.trace("Не вдалося отримати lastOpenedBookId з БД для колекції {}", collectionId, e);
        }
        return null;
    }

    @Override
    public void clearSession(String collectionId) {
        if (collectionId == null || collectionId.isEmpty()) {
            return;
        }
        String prefKey = PREF_KEY_PREFIX + collectionId;
        String clearedKey = PREF_CLEARED_PREFIX + collectionId;
        prefs.remove(prefKey);
        // Tombstone prevents a stale SQLite mirror from resurrecting the cleared book when
        // DELETE cannot be committed because of a temporary database lock. A later save
        // removes the tombstone before mirroring its new value to SQLite.
        prefs.putBoolean(clearedKey, true);
        try {
            busyRetry.run("session clear", () -> queryExecutor.update("DELETE FROM session WHERE key = ?", prefKey));
            prefs.remove(clearedKey);
            log.debug("Очищено session для колекції {}", collectionId);
        } catch (Exception e) {
            log.warn("Не вдалося очистити session для колекції {}; збережено локальний tombstone", collectionId, e);
        }
    }
}