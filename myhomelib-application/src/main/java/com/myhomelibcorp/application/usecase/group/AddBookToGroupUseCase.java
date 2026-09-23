package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AddBookToGroupUseCase {

    private final GroupRepository groupRepository;
    private final GroupMutationExecutor mutationExecutor;

    public void execute(Long groupId, String bookId) {
        execute(groupId, bookId, null);
    }

    public void execute(Long groupId, String bookId, String expectedCollectionId) {
        if (groupId == null || bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("Group ID and Book ID cannot be null");
        }
        mutationExecutor.execute(expectedCollectionId, () -> groupRepository.addBookToGroup(groupId, bookId));
    }
}
