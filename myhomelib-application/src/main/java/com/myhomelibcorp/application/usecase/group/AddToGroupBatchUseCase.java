package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AddToGroupBatchUseCase {

    private final GroupRepository groupRepository;

    @Transactional(transactionManager = "collectionTransactionManager")
    public void execute(Long groupId, List<BookId> bookIds) {
        if (groupId == null || bookIds == null || bookIds.isEmpty()) {
            return;
        }
        for (BookId bookId : bookIds) {
            groupRepository.addBookToGroup(groupId, bookId.asString());
        }
    }
}