package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

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

    public void upsertArtifact(BookId bookId, BookArtifact artifact, boolean makePreferred) {
        if (bookId == null) throw new IllegalArgumentException("Book id is required");
        if (artifact == null) throw new IllegalArgumentException("Artifact is required");
        executeSynchronized(List.of(bookId), () -> commands.upsertArtifact(bookId, artifact, makePreferred));
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

    /** Read/modify/write a bounded set in one transaction with one derived-index notification. */
    public List<Book> updateBatch(List<BookId> affectedIds, Function<BookId, Book> update,
                                 BooleanSupplier cancelled) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(cancelled, "cancelled");
        List<BookId> ids = normalize(affectedIds);
        if (cancelled.getAsBoolean()) throw new CancellationException("Metadata update cancelled");
        if (ids.isEmpty()) return List.of();
        return transactionTemplate.execute(status -> {
            List<Book> updated = ids.stream().map(id -> {
                if (cancelled.getAsBoolean()) throw new CancellationException("Metadata update cancelled");
                Book book = Objects.requireNonNull(update.apply(id), "updated book");
                if (!id.equals(book.getId())) throw new IllegalArgumentException("Updated book id changed");
                return book;
            }).toList();
            if (cancelled.getAsBoolean()) throw new CancellationException("Metadata update cancelled");
            commands.saveBatch(updated);
            if (cancelled.getAsBoolean()) throw new CancellationException("Metadata update cancelled");
            searchIndexSynchronizer.synchronizeAfterCommit(ids);
            return updated;
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
