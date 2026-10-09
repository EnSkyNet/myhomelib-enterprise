package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Component
public class UpdateProgressBatchUseCase {
    private final BookBatchEditSupport batchEdit;

    public UpdateProgressBatchUseCase(BookCommandRepository repository,
                                      SearchIndexSynchronizer searchIndexSynchronizer,
                                      LibraryOperationCoordinator operationCoordinator) {
        this(repository, searchIndexSynchronizer, operationCoordinator, null);
    }

    @Autowired
    public UpdateProgressBatchUseCase(BookCommandRepository repository,
                                      SearchIndexSynchronizer searchIndexSynchronizer,
                                      LibraryOperationCoordinator operationCoordinator,
                                      @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate) {
        this.batchEdit = new BookBatchEditSupport(repository, searchIndexSynchronizer,
                operationCoordinator, transactionTemplate);
    }

    public void execute(List<BookId> bookIds, int progress) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("Progress must be between 0 and 100");
        }
        batchEdit.execute(bookIds, repository -> repository.updateProgressBatch(bookIds, progress));
    }
}
