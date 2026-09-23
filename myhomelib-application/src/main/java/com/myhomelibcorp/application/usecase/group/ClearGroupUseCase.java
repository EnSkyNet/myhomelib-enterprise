package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ClearGroupUseCase {
    private final GroupRepository groupRepository;
    private final GroupMutationExecutor mutationExecutor;

    public void execute(Long groupId) {
        execute(groupId, null);
    }

    public void execute(Long groupId, String expectedCollectionId) {
        if (groupId == null) throw new IllegalArgumentException("Group ID cannot be null");
        mutationExecutor.execute(expectedCollectionId, () -> groupRepository.deleteAllBooksFromGroup(groupId));
    }
}
