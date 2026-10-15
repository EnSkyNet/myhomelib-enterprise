package com.myhomelibcorp.infrastructure.importer;

import com.myhomelibcorp.application.imports.duplicate.DuplicateDetector;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.config.CollectionTransactionConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Integration check against a physical SQLite database and real Spring JDBC transaction boundaries. */
class ArchiveImportSqliteAtomicityTest {
    @TempDir Path temporary;

    @Test
    void rollbackOfSecondBatchLeavesNoPartialCatalogOrStaleCache() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temporary.resolve("archive.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE books (id TEXT PRIMARY KEY)");
        BookCommandRepository commands = mock(BookCommandRepository.class);
        doAnswer(call -> {
            List<Book> books = call.getArgument(0);
            for (Book b : books) jdbc.update("INSERT INTO books(id) VALUES (?)", b.getId().asString());
            return null;
        }).when(commands).saveBatch(anyList());
        DuplicateDetector duplicates = mock(DuplicateDetector.class);
        when(duplicates.resolveBatch(anyList())).thenReturn(DuplicateDetector.BatchResolution.empty());
        CollectionManager collections = mock(CollectionManager.class);
        when(collections.getCurrentDataSource()).thenReturn(ds);
        BookSaver saver = new BookSaver(commands, mock(BookQueryRepository.class),
                mock(ApplicationEventPublisher.class), duplicates, mock(SearchIndexer.class),
                new CollectionTransactionConfig().collectionTransactionTemplate(
                        new CollectionTransactionConfig().collectionTransactionManager(collections)),
                new LibraryOperationCoordinator(), mock(CollectionLifecyclePort.class));
        Book first = mockBook(1), second = mockBook(2);

        assertThrows(IllegalStateException.class, () -> saver.inAtomicArchiveImport(() -> {
            saver.saveBatchWithResult(List.of(first), false, DuplicatePolicy.SKIP);
            saver.saveBatchWithResult(List.of(second), false, DuplicatePolicy.SKIP);
            throw new IllegalStateException("archive damaged after second batch");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM books", Integer.class));
        verify(duplicates).clearCache();

        saver.inAtomicArchiveImport(() -> {
            saver.saveBatchWithResult(List.of(first), false, DuplicatePolicy.SKIP);
            saver.saveBatchWithResult(List.of(second), false, DuplicatePolicy.SKIP);
            return null;
        });
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM books", Integer.class));
    }

    private static Book mockBook(int id) {
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(BookId.fromString("00000000-0000-0000-0000-" + String.format("%012d", id)));
        return book;
    }
}
