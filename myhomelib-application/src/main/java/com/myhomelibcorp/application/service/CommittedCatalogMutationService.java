package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Executes authoritative collection-DB mutations in one collection transaction and
 * schedules derived Lucene synchronization only after that transaction commits.
 */
@Service
public class CommittedCatalogMutationService {
    private final BookCommandRepository commands;
    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final TransactionTemplate transactionTemplate;

    public CommittedCatalogMutationService(
            BookCommandRepository commands,
            SearchIndexSynchronizer searchIndexSynchronizer,
            @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate) {
        this.commands = commands;
        this.searchIndexSynchronizer = searchIndexSynchronizer;
        this.transactionTemplate = transactionTemplate;
    }

    public Book save(Book book) {
        if (book == null || book.getId() == null) throw new IllegalArgumentException("Book with id is required");
        executeSynchronized(List.of(book.getId()), () -> commands.save(book));
        return book;
    }

    public void saveBatch(List<Book> books) {
        if (books == null || books.isEmpty()) return;
        List<Book> stable = books.stream().filter(java.util.Objects::nonNull).toList();
        if (stable.isEmpty()) return;
        executeSynchronized(stable.stream().map(Book::getId).toList(), () -> commands.saveBatch(stable));
    }

    public void updateAvailability(Book book, boolean local) {
        if (book == null || book.getId() == null) return;
        executeSynchronized(List.of(book.getId()), () -> {
            if (local) {
                commands.updateStorage(book.getId(), book.getCollectionRoot(), book.getFolder(),
                        book.getFileName(), book.getArchiveEntry(), true);
            } else {
                commands.markStorageMissing(book.getId());
            }
        });
    }

    public void executeSynchronized(List<BookId> affectedIds, Runnable databaseMutation) {
        if (databaseMutation == null) throw new IllegalArgumentException("Database mutation is required");
        List<BookId> ids = normalize(affectedIds);
        transactionTemplate.execute(status -> {
            databaseMutation.run();
            searchIndexSynchronizer.synchronizeAfterCommit(ids);
            return null;
        });
    }

    private static List<BookId> normalize(List<BookId> source) {
        if (source == null || source.isEmpty()) return List.of();
        LinkedHashSet<BookId> unique = new LinkedHashSet<>();
        for (BookId id : source) if (id != null) unique.add(id);
        return List.copyOf(unique);
    }
}
