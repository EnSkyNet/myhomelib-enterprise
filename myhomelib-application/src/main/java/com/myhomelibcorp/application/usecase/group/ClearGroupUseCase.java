package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ClearGroupUseCase {
    private final GroupRepository groupRepository;

    @Transactional(transactionManager = "collectionTransactionManager")
    public void execute(Long groupId) {
        if (groupId == null) throw new IllegalArgumentException("Group ID cannot be null");
        groupRepository.deleteAllBooksFromGroup(groupId);
    }
}
