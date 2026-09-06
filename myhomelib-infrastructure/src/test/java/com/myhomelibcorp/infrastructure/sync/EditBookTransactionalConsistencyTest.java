package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.application.usecase.book.EditBookUseCase;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class EditBookTransactionalConsistencyTest {
    @TempDir Path tempDir;

    @Test
    void relationFailureRollsBackEditAndNeverSchedulesLucene() {
        TestDb db = database("rollback.db");
        Book current = book("Old title");
        db.jdbc.update("INSERT INTO books(id,title) VALUES (?,?)", current.getId().asString(), current.getTitle());

        BookQueryRepository queries = mock(BookQueryRepository.class);
        when(queries.findById(current.getId())).thenReturn(Optional.of(current));
        BookCommandRepository commands = mock(BookCommandRepository.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        doAnswer(inv -> {
            Book updated = inv.getArgument(0);
            db.jdbc.update("UPDATE books SET title=? WHERE id=?", updated.getTitle(), updated.getId().asString());
            throw new IllegalStateException("author relation write failed");
        }).when(commands).save(any(Book.class));

        EditBookUseCase useCase = useCase(queries, commands, synchronizer, db.tx);
        assertThrows(IllegalStateException.class, () -> useCase.execute(request(current.getId(), "New title")));

        assertThat(db.jdbc.queryForObject("SELECT title FROM books WHERE id=?", String.class, current.getId().asString()))
                .isEqualTo("Old title");
        verifyNoInteractions(synchronizer);
    }

    @Test
    void luceneFailureKeepsCommittedEditAndLeavesIndexDirtyForRecovery() {
        TestDb db = database("lucene.db");
        Book current = book("Old title");
        db.jdbc.update("INSERT INTO books(id,title) VALUES (?,?)", current.getId().asString(), current.getTitle());

        AtomicReference<Book> saved = new AtomicReference<>();
        BookQueryRepository queries = mock(BookQueryRepository.class);
        when(queries.findById(current.getId())).thenReturn(Optional.of(current));
        when(queries.findByIds(anyList())).thenAnswer(inv -> List.of(saved.get()));

        BookCommandRepository commands = mock(BookCommandRepository.class);
        doAnswer(inv -> {
            Book updated = inv.getArgument(0);
            saved.set(updated);
            db.jdbc.update("UPDATE books SET title=? WHERE id=?", updated.getTitle(), updated.getId().asString());
            return updated;
        }).when(commands).save(any(Book.class));

        SearchIndexer indexer = mock(SearchIndexer.class);
        doThrow(new IllegalStateException("selective Lucene failure")).when(indexer).indexBook(any(Book.class));
        doThrow(new IllegalStateException("rebuild Lucene failure")).when(indexer).rebuildIndex();
        SearchIndexLifecycle lifecycle = mock(SearchIndexLifecycle.class);
        SearchIndexSynchronizer synchronizer = new SearchIndexSynchronizer(queries, indexer, lifecycle);

        EditBookUseCase useCase = useCase(queries, commands, synchronizer, db.tx);
        Book result = useCase.execute(request(current.getId(), "Committed title"));

        assertThat(result.getTitle()).isEqualTo("Committed title");
        assertThat(db.jdbc.queryForObject("SELECT title FROM books WHERE id=?", String.class, current.getId().asString()))
                .isEqualTo("Committed title");
        verify(lifecycle, atLeastOnce()).markCurrentIndexDirty();
        verify(lifecycle, never()).markCurrentIndexSynchronized();
        verify(indexer).rollbackAtomicUpdate();
        verify(indexer).rebuildIndex();
    }

    private EditBookUseCase useCase(BookQueryRepository queries, BookCommandRepository commands,
                                    SearchIndexSynchronizer synchronizer, TransactionTemplate tx) {
        CommittedCatalogMutationService mutations = new CommittedCatalogMutationService(commands, synchronizer, tx);
        return new EditBookUseCase(queries, mutations);
    }

    private TestDb database(String name) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(name));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT NOT NULL)");
        return new TestDb(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static Book book(String title) {
        return Book.builder().id(BookId.generate()).title(title).authors(List.of(new Author("Old", "", "Author")))
                .metadata(BookMetadata.builder().language(LanguageCode.of("uk")).rate(3).progress(20).build())
                .file(BookFile.empty()).build();
    }

    private static EditBookUseCase.Request request(BookId id, String title) {
        return new EditBookUseCase.Request(id, title, List.of(new Author("New", "", "Author")),
                "Series", 2, LanguageCode.of("en"), 2026, "Publisher", "keywords", "annotation", "review");
    }

    private record TestDb(JdbcTemplate jdbc, TransactionTemplate tx) { }
}
