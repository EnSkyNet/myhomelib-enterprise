package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MarkAsReadBatchUseCase {

    private final BookCommandRepository bookCommandRepository;
    private final SearchIndexSynchronizer searchIndexSynchronizer;

    @Transactional(transactionManager = "collectionTransactionManager")
    public void execute(List<BookId> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        bookCommandRepository.updateProgressBatch(bookIds, 100);
        searchIndexSynchronizer.synchronizeAfterCommit(bookIds);
    }
}