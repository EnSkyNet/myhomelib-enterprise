package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteSessionRepositoryTest {

    @Test
    void shortDatabaseLockNeverBreaksSessionSaveAndPreferencesRemainFallback() {
        QueryExecutor queryExecutor = mock(QueryExecutor.class);
        when(queryExecutor.update(anyString(), any(Object[].class)))
                .thenThrow(new IllegalStateException("database is locked"));
        when(queryExecutor.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("database is locked"));

        SqliteSessionRepository repository = new SqliteSessionRepository(queryExecutor, new SqliteBusyRetryExecutor());
        String collectionId = "lock-test-" + UUID.randomUUID();
        String bookId = UUID.randomUUID().toString();

        assertThatCode(() -> repository.saveLastOpenedBookId(collectionId, bookId)).doesNotThrowAnyException();
        assertThat(repository.getLastOpenedBookId(collectionId)).isEqualTo(bookId);
        assertThatCode(() -> repository.clearSession(collectionId)).doesNotThrowAnyException();
    }
}
