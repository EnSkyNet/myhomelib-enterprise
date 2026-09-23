package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.domain.model.group.Group;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateGroupUseCase {
    private final GroupRepository groupRepository;
    private final GroupMutationExecutor mutationExecutor;

    public Group execute(String groupName) {
        return execute(groupName, null);
    }

    public Group execute(String groupName, String expectedCollectionId) {
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("Group name cannot be empty");
        }
        Group group = new Group(groupName, true);
        return mutationExecutor.execute(expectedCollectionId, () -> groupRepository.save(group));
    }
}
