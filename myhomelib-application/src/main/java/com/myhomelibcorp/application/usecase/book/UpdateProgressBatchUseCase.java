package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class UpdateProgressBatchUseCase {

    private final BookCommandRepository bookCommandRepository;

    @Transactional(transactionManager = "collectionTransactionManager")
    public void execute(List<BookId> bookIds, int progress) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        bookCommandRepository.updateProgressBatch(bookIds, progress);
    }
}