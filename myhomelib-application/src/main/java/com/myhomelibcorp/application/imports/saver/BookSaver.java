package com.myhomelibcorp.application.imports.saver;

import static com.myhomelibcorp.application.operation.LibraryOperationLeaseSupport.withSharedLease;
import com.myhomelibcorp.application.imports.duplicate.DuplicateDetector;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Component
@Slf4j
public class BookSaver {

    private final BookCommandRepository bookCommandRepository;
    private final BookQueryRepository bookQueryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final DuplicateDetector duplicateDetector;
    private final SearchIndexer searchIndexer;
    private final TransactionTemplate transactionTemplate;
    private final LibraryOperationCoordinator operationCoordinator;
    private final CollectionLifecyclePort collectionLifecyclePort;

    public BookSaver(
            BookCommandRepository bookCommandRepository,
            BookQueryRepository bookQueryRepository,
            ApplicationEventPublisher eventPublisher,
            DuplicateDetector duplicateDetector,
            SearchIndexer searchIndexer,
            @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate,
            LibraryOperationCoordinator operationCoordinator,
            CollectionLifecyclePort collectionLifecyclePort) {
        this.bookCommandRepository = bookCommandRepository;
        this.bookQueryRepository = bookQueryRepository;
        this.eventPublisher = eventPublisher;
        this.duplicateDetector = duplicateDetector;
        this.searchIndexer = searchIndexer;
        this.transactionTemplate = transactionTemplate;
        this.operationCoordinator = operationCoordinator;
        this.collectionLifecyclePort = collectionLifecyclePort;
    }

    public boolean saveBook(Book book, boolean indexAfterSave, DuplicatePolicy policy) {
        if (book == null) return false;

        Book effective = book;
        if (policy != DuplicatePolicy.SAVE_AS_NEW) {
            Optional<Book> existing = duplicateDetector.findDuplicate(book);
            if (policy == DuplicatePolicy.SKIP && existing.isPresent()) {
                log.debug("Дублікат пропущено: {}", book.getTitle());
                return false;
            }
            if ((policy == DuplicatePolicy.MERGE || policy == DuplicatePolicy.REPLACE) && existing.isPresent()) {
                effective = ImportBookMergePolicy.mergePreservingUserState(existing.get(), book);
            }
        }

        Book stableEffective = effective;
        transactionTemplate.execute(status -> {
            bookCommandRepository.save(stableEffective);
            return null;
        });
        duplicateDetector.addKey(stableEffective);
        if (indexAfterSave) {
            searchIndexer.indexBook(stableEffective);
            searchIndexer.commit();
        }
        log.debug("Книгу збережено: {}", stableEffective.getTitle());
        return true;
    }

    public int saveBatch(List<Book> books, boolean indexAfterSave, DuplicatePolicy policy) {
        return saveBatchReturningSaved(books, indexAfterSave, policy).size();
    }

    /** Batch save with exact saved-book feedback. */
    public List<Book> saveBatchReturningSaved(List<Book> books, boolean indexAfterSave, DuplicatePolicy policy) {
        return saveBatchReturningSaved(books, indexAfterSave, policy, saved -> { });
    }

    /**
     * Batch save with an optional action that participates in the same collection-DB transaction.
     */
    public List<Book> saveBatchReturningSaved(List<Book> books,
                                               boolean indexAfterSave,
                                               DuplicatePolicy policy,
                                               Consumer<List<Book>> afterDatabaseSave) {
        return saveBatchWithResult(books, indexAfterSave, policy, afterDatabaseSave).savedBooks();
    }

    /**
     * Rich bounded batch result used by import orchestration so inserted and updated IDs are not
     * conflated. Duplicate resolution is one bounded DB operation per chunk, not N+1 lookups.
     */
    public BatchSaveResult saveBatchWithResult(List<Book> books,
                                               boolean indexAfterSave,
                                               DuplicatePolicy policy) {
        return saveBatchWithResult(books, indexAfterSave, policy, saved -> { });
    }

    public BatchSaveResult saveBatchWithResult(List<Book> books,
                                               boolean indexAfterSave,
                                               DuplicatePolicy policy,
                                               Consumer<List<Book>> afterDatabaseSave) {
        if (books == null || books.isEmpty()) return BatchSaveResult.empty();

        DuplicateDetector.BatchResolution duplicates = policy == DuplicatePolicy.SAVE_AS_NEW
                ? DuplicateDetector.BatchResolution.empty()
                : duplicateDetector.resolveBatch(books);

        List<Book> inserted = new ArrayList<>();
        List<Book> updated = new ArrayList<>();
        List<Book> skipped = new ArrayList<>();

        for (Book incoming : books) {
            if (incoming == null) continue;
            if (duplicates.isRepeated(incoming) && policy != DuplicatePolicy.SAVE_AS_NEW) {
                skipped.add(incoming);
                continue;
            }

            Optional<Book> existing = duplicates.existingFor(incoming);
            if (existing.isEmpty()) {
                inserted.add(incoming);
                continue;
            }

            if (policy == DuplicatePolicy.SKIP) {
                skipped.add(incoming);
            } else if (policy == DuplicatePolicy.MERGE || policy == DuplicatePolicy.REPLACE) {
                updated.add(ImportBookMergePolicy.mergePreservingUserState(existing.get(), incoming));
            } else {
                inserted.add(incoming);
            }
        }

        List<Book> saved = new ArrayList<>(inserted.size() + updated.size());
        saved.addAll(inserted);
        saved.addAll(updated);
        if (saved.isEmpty()) {
            return new BatchSaveResult(List.of(), List.of(), List.copyOf(skipped));
        }

        List<Book> stableSaved = List.copyOf(saved);
        transactionTemplate.execute(status -> {
            bookCommandRepository.saveBatch(stableSaved);
            if (afterDatabaseSave != null) afterDatabaseSave.accept(stableSaved);
            return null;
        });
        duplicateDetector.addAllKeys(stableSaved);

        if (indexAfterSave) {
            searchIndexer.indexAll(stableSaved);
            searchIndexer.commit();
        }

        log.info("Збережено {} книг (нових {}, оновлено {}, пропущено {})",
                stableSaved.size(), inserted.size(), updated.size(), skipped.size());
        return new BatchSaveResult(List.copyOf(inserted), List.copyOf(updated), List.copyOf(skipped));
    }

    /**
     * Видаляє книгу за ID та публікує подію BookDeletedEvent.
     */
    public void deleteBook(BookId bookId) {
        deleteBook(bookId, null);
    }

    /**
     * Deletes a book only if the collection that originated the UI/request is still active
     * after the EDIT lifecycle barrier has been acquired.
     */
    public void deleteBook(BookId bookId, String expectedCollectionId) {
        if (bookId == null) {
            throw new IllegalArgumentException("BookId cannot be null");
        }
        withEditLease(() -> {
            assertExpectedCollection(expectedCollectionId);
            Optional<Book> bookOpt = bookQueryRepository.findById(bookId);
            if (bookOpt.isEmpty()) {
                log.warn("Спроба видалити неіснуючу книгу: {}", bookId);
                return null;
            }

            transactionTemplate.execute(status -> {
                bookCommandRepository.deleteById(bookId);
                eventPublisher.publishEvent(new BookDeletedEvent(bookId));
                return null;
            });

            log.debug("Книгу видалено: {}", bookId);
            return null;
        });
    }

    /**
     * Deletes a selected set as one collection-DB transaction. A failure in any delete rolls back
     * the whole batch; BookDeletedEvent handlers run only after the transaction commits.
     * Missing IDs are ignored to preserve the single-delete compatibility contract.
     */
    public int deleteBooks(List<BookId> bookIds) {
        return deleteBooks(bookIds, null);
    }

    public int deleteBooks(List<BookId> bookIds, String expectedCollectionId) {
        if (bookIds == null || bookIds.isEmpty()) return 0;
        if (bookIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("BookIds cannot contain null values");
        }
        return withEditLease(() -> {
            assertExpectedCollection(expectedCollectionId);
            List<BookId> requested = bookIds.stream().distinct().toList();
            Integer deleted = transactionTemplate.execute(status -> {
                List<Book> existing = bookQueryRepository.findByIds(requested);
                if (existing.isEmpty()) return 0;
                List<BookId> existingIds = existing.stream().map(Book::getId).distinct().toList();
                for (BookId id : existingIds) {
                    bookCommandRepository.deleteById(id);
                    eventPublisher.publishEvent(new BookDeletedEvent(id));
                }
                return existingIds.size();
            });
            int count = deleted == null ? 0 : deleted;
            log.debug("Пакетно видалено {} книг", count);
            return count;
        });
    }

    private void assertExpectedCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) return;
        Collection active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null
                || !expectedCollectionId.trim().equals(active.getId().trim())) {
            throw new IllegalStateException("Активна колекція змінилася до видалення книги");
        }
    }

    private <T> T withEditLease(java.util.function.Supplier<T> action) {
        return withSharedLease(operationCoordinator, LibraryOperationType.EDIT, action);
    }

    public record BatchSaveResult(List<Book> insertedBooks,
                                  List<Book> updatedBooks,
                                  List<Book> skippedBooks) {
        public BatchSaveResult {
            insertedBooks = insertedBooks == null ? List.of() : List.copyOf(insertedBooks);
            updatedBooks = updatedBooks == null ? List.of() : List.copyOf(updatedBooks);
            skippedBooks = skippedBooks == null ? List.of() : List.copyOf(skippedBooks);
        }

        public static BatchSaveResult empty() {
            return new BatchSaveResult(List.of(), List.of(), List.of());
        }

        public List<Book> savedBooks() {
            if (updatedBooks.isEmpty()) return insertedBooks;
            if (insertedBooks.isEmpty()) return updatedBooks;
            ArrayList<Book> all = new ArrayList<>(insertedBooks.size() + updatedBooks.size());
            all.addAll(insertedBooks);
            all.addAll(updatedBooks);
            return List.copyOf(all);
        }
    }
}
