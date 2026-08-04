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
    private final Preferences prefs = Preferences.userNodeForPackage(SqliteSessionRepository.class);

    private static final String PREF_KEY_PREFIX = "lastOpenedBookId_";

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
        prefs.put(prefKey, bookId);

        try {
            String sql = "INSERT OR REPLACE INTO session (key, value) VALUES (?, ?)";
            queryExecutor.update(sql, prefKey, bookId);
            log.debug("Збережено останню книгу для колекції {}: {}", collectionId, bookId);
        } catch (Exception e) {
            log.warn("Не вдалося зберегти lastOpenedBookId в БД для колекції {}", collectionId, e);
        }
    }

    @Override
    public String getLastOpenedBookId(String collectionId) {
        if (collectionId == null || collectionId.isEmpty()) {
            log.warn("Спроба отримати bookId без collectionId");
            return null;
        }

        String prefKey = PREF_KEY_PREFIX + collectionId;

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

        String prefsValue = prefs.get(prefKey, null);
        if (prefsValue != null) {
            log.debug("Отримано останню книгу для колекції {} з Preferences: {}", collectionId, prefsValue);
        }
        return prefsValue;
    }

    @Override
    public void clearSession(String collectionId) {
        if (collectionId == null || collectionId.isEmpty()) {
            return;
        }
        String prefKey = PREF_KEY_PREFIX + collectionId;
        prefs.remove(prefKey);
        try {
            queryExecutor.update("DELETE FROM session WHERE key = ?", prefKey);
            log.debug("Очищено session для колекції {}", collectionId);
        } catch (Exception e) {
            log.warn("Не вдалося очистити session для колекції {}", collectionId, e);
        }
    }
}