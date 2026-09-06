package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Fault-injection proof for the FolderSync write boundary: if a repository implementation fails
 * after its authoritative book-row write but before relation writes complete, the application-level
 * collection transaction rolls the first statement back and never schedules Lucene synchronization.
 */
class CommittedCatalogMutationServiceTransactionTest {

    @TempDir
    Path tempDir;

    @Test
    void relationFailureRollsBackPreviouslyWrittenBookRowAndNeverSchedulesLucene() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("catalog.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE book_authors(book_id TEXT NOT NULL, author TEXT NOT NULL)");

        BookCommandRepository commands = mock(BookCommandRepository.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CommittedCatalogMutationService service =
                new CommittedCatalogMutationService(commands, synchronizer, transaction);

        BookId id = BookId.generate();
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        doAnswer(invocation -> {
            jdbc.update("INSERT INTO books(id, title) VALUES (?, ?)", id.asString(), "fault-injected");
            // Equivalent to a failure at the author/genre relation boundary inside a multi-statement save().
            throw new IllegalStateException("author relation write failed");
        }).when(commands).save(book);

        assertThrows(IllegalStateException.class, () -> service.save(book));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM books", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_authors", Integer.class)).isZero();
        verifyNoInteractions(synchronizer);
    }
}
