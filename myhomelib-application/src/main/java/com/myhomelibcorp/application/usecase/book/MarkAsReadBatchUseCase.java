package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Component
public class MarkAsReadBatchUseCase {

    private final BookCommandRepository bookCommandRepository;
    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final LibraryOperationCoordinator operationCoordinator;
    private final TransactionTemplate transactionTemplate;

    public MarkAsReadBatchUseCase(
            BookCommandRepository bookCommandRepository,
            SearchIndexSynchronizer searchIndexSynchronizer,
            LibraryOperationCoordinator operationCoordinator) {
        this(bookCommandRepository, searchIndexSynchronizer, operationCoordinator, null);
    }

    @Autowired
    public MarkAsReadBatchUseCase(
            BookCommandRepository bookCommandRepository,
            SearchIndexSynchronizer searchIndexSynchronizer,
            LibraryOperationCoordinator operationCoordinator,
            @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate) {
        this.bookCommandRepository = bookCommandRepository;
        this.searchIndexSynchronizer = searchIndexSynchronizer;
        this.operationCoordinator = operationCoordinator;
        this.transactionTemplate = transactionTemplate;
    }

    public void execute(List<BookId> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        if (bookIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Book ids cannot contain null values");
        }
        withEditTransaction(() -> {
            bookCommandRepository.updateProgressBatch(bookIds, 100);
            searchIndexSynchronizer.synchronizeAfterCommit(bookIds);
        });
    }

    private void withEditTransaction(Runnable action) {
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EDIT);
        try {
            if (transactionTemplate == null) {
                action.run();
            } else {
                transactionTemplate.executeWithoutResult(status -> action.run());
            }
            lease.markCompleted("");
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }
}