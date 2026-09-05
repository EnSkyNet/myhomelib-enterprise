package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookListRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SqliteBookQueryRepositoryLazyStreamTest {

    @Test
    void streamSearchSnapshotsDoesNotReadDatabaseUntilConsumed() {
        CollectionManager collectionManager = mock(CollectionManager.class);
        SqliteBookQueryRepository repository = new SqliteBookQueryRepository(
                collectionManager,
                mock(BookRowMapper.class),
                mock(BookListRowMapper.class),
                mock(BookAuthorHelper.class),
                mock(BookGenreHelper.class),
                mock(BookQueryBuilder.class));

        try (var snapshots = repository.streamSearchSnapshots()) {
            assertThat(snapshots).isNotNull();
            verifyNoInteractions(collectionManager);
        }
    }
}
