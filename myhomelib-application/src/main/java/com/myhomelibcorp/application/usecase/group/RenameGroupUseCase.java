package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.domain.model.group.Group;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RenameGroupUseCase {

    private final GroupRepository groupRepository;
    private final GroupMutationExecutor mutationExecutor;

    public Group execute(Long groupId, String newName) {
        return execute(groupId, newName, null);
    }

    public Group execute(Long groupId, String newName, String expectedCollectionId) {
        if (groupId == null) {
            throw new IllegalArgumentException("Group ID cannot be null");
        }
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("New group name cannot be empty");
        }
        return mutationExecutor.execute(expectedCollectionId, () -> {
            Group existing = groupRepository.findById(groupId)
                    .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));
            return groupRepository.save(existing.rename(newName));
        });
    }
}
