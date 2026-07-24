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

    @Override
    public void saveLastOpenedBookId(String bookId) {
        if (bookId == null || bookId.isEmpty()) {
            log.warn("Спроба зберегти порожній bookId");
            return;
        }
        prefs.put("lastOpenedBookId", bookId);
        try {
            String sql = "INSERT OR REPLACE INTO session (key, value) VALUES ('lastOpenedBookId', ?)";
            queryExecutor.update(sql, bookId);
            log.debug("Збережено останню книгу в БД: {}", bookId);
        } catch (Exception e) {
            log.warn("Не вдалося зберегти lastOpenedBookId в БД", e);
        }
    }

    @Override
    public String getLastOpenedBookId() {
        try {
            String sql = "SELECT value FROM session WHERE key = 'lastOpenedBookId'";
            String value = queryExecutor.queryForObject(sql, String.class);
            if (value != null && !value.isEmpty()) {
                log.debug("Отримано останню книгу з БД: {}", value);
                return value;
            }
        } catch (Exception e) {
            log.trace("Не вдалося отримати lastOpenedBookId з БД", e);
        }
        String prefsValue = prefs.get("lastOpenedBookId", null);
        if (prefsValue != null) {
            log.debug("Отримано останню книгу з Preferences: {}", prefsValue);
        }
        return prefsValue;
    }
}