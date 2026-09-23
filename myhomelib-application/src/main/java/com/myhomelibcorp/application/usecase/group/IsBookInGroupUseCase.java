package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IsBookInGroupUseCase {

    private final GroupRepository groupRepository;
    private final GroupQueryExecutor queryExecutor;

    public boolean execute(Long groupId, String bookId) {
        if (groupId == null || bookId == null || bookId.isBlank()) return false;
        return queryExecutor.execute(() -> groupRepository.containsBook(groupId, bookId));
    }
}
