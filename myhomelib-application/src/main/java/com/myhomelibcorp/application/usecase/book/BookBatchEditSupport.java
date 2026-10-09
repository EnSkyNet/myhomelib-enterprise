package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationLeaseSupport;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Shared transaction/validation/index-sync mechanics for batch book edits. */
final class BookBatchEditSupport {
    private final BookCommandRepository repository;
    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final LibraryOperationCoordinator operationCoordinator;
    private final TransactionTemplate transactionTemplate;

    BookBatchEditSupport(BookCommandRepository repository,
                         SearchIndexSynchronizer searchIndexSynchronizer,
                         LibraryOperationCoordinator operationCoordinator,
                         TransactionTemplate transactionTemplate) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.searchIndexSynchronizer = Objects.requireNonNull(searchIndexSynchronizer, "searchIndexSynchronizer");
        this.operationCoordinator = Objects.requireNonNull(operationCoordinator, "operationCoordinator");
        this.transactionTemplate = transactionTemplate;
    }

    void execute(List<BookId> bookIds, Consumer<BookCommandRepository> mutation) {
        if (bookIds == null || bookIds.isEmpty()) return;
        if (bookIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Book ids cannot contain null values");
        }
        Objects.requireNonNull(mutation, "mutation");
        LibraryOperationLeaseSupport.withSharedTransaction(
                operationCoordinator,
                LibraryOperationType.EDIT,
                transactionTemplate,
                () -> {
                    mutation.accept(repository);
                    searchIndexSynchronizer.synchronizeAfterCommit(bookIds);
                });
    }
}
