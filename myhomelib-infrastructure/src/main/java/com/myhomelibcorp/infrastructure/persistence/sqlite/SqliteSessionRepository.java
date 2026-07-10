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
    public void saveLastOpenedBookId(Long bookId) {
        prefs.putLong("lastOpenedBookId", bookId);
        try {
            String sql = "INSERT OR REPLACE INTO session (key, value) VALUES ('lastOpenedBookId', ?)";
            queryExecutor.update(sql, String.valueOf(bookId));
        } catch (Exception e) {
            log.warn("Failed to save lastOpenedBookId to DB", e);
        }
    }

    @Override
    public Long getLastOpenedBookId() {
        try {
            String sql = "SELECT value FROM session WHERE key = 'lastOpenedBookId'";
            String value = queryExecutor.queryForObject(sql, String.class);
            if (value != null) {
                return Long.parseLong(value);
            }
        } catch (Exception e) {
            // ignore
        }
        long id = prefs.getLong("lastOpenedBookId", -1);
        return id == -1 ? null : id;
    }
}