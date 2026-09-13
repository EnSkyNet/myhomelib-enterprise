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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class MetadataBatchTransactionTest {
    @TempDir Path temp;

    @Test void secondWriteFailureRollsBackTheFirstBook() { verifyBatch(true, false); }
    @Test void cancellationAfterDatabaseWriteRollsBackEveryBook() { verifyBatch(false, true); }
    @Test void successCommitsEveryBookAndSchedulesOneIndexUpdate() { verifyBatch(false, false); }

    private void verifyBatch(boolean fail, boolean cancel) {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("batch.db"));
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT NOT NULL, progress INTEGER NOT NULL)");
        List<BookId> ids = List.of(BookId.generate(), BookId.generate());
        for (BookId id : ids) jdbc.update("INSERT INTO books VALUES (?, 'Old', 71)", id.asString());
        BookCommandRepository commands = mock(BookCommandRepository.class);
        SearchIndexSynchronizer search = mock(SearchIndexSynchronizer.class);
        var service = new CommittedCatalogMutationService(commands, search,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        AtomicBoolean cancelled = new AtomicBoolean();
        doAnswer(call -> {
            List<Book> updates = call.getArgument(0);
            for (Book book : updates) {
                jdbc.update("UPDATE books SET title=? WHERE id=?", book.getTitle(), book.getId().asString());
                if (fail) throw new IllegalStateException("Injected write failure");
            }
            cancelled.set(cancel);
            return null;
        }).when(commands).saveBatch(anyList());
        Runnable update = () -> service.updateBatch(ids, id -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("SELECT title FROM books WHERE id=?", String.class, id.asString())).isEqualTo("Old");
            return Book.builder().id(id).title("New").build();
        }, cancelled::get);

        if (fail) assertThrows(IllegalStateException.class, update::run);
        else if (cancel) assertThrows(CancellationException.class, update::run);
        else update.run();

        assertThat(jdbc.queryForList("SELECT title FROM books ORDER BY id", String.class))
                .containsOnly(fail || cancel ? "Old" : "New");
        assertThat(jdbc.queryForList("SELECT progress FROM books", Integer.class)).containsOnly(71);
        if (fail || cancel) verifyNoInteractions(search);
        else verify(search, times(1)).synchronizeAfterCommit(ids);
    }
}
