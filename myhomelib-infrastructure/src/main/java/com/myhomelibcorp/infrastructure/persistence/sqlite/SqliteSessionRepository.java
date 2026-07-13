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
        prefs.put("lastOpenedBookId", bookId);
        try {
            String sql = "INSERT OR REPLACE INTO session (key, value) VALUES ('lastOpenedBookId', ?)";
            queryExecutor.update(sql, bookId);
        } catch (Exception e) {
            log.warn("Failed to save lastOpenedBookId to DB", e);
        }
    }

    @Override
    public String getLastOpenedBookId() {
        try {
            String sql = "SELECT value FROM session WHERE key = 'lastOpenedBookId'";
            return queryExecutor.queryForObject(sql, String.class);
        } catch (Exception e) {
            // ignore
        }
        return prefs.get("lastOpenedBookId", null);
    }
}