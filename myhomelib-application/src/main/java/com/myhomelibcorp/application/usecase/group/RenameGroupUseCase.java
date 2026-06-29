package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.GroupRepository;
import com.myhomelibcorp.domain.model.group.Group;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RenameGroupUseCase {

    private final GroupRepository groupRepository;

    public Group execute(Long groupId, String newName) {
        if (groupId == null) {
            throw new IllegalArgumentException("Group ID cannot be null");
        }
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("New group name cannot be empty");
        }
        Group existing = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));
        Group renamed = existing.rename(newName);
        return groupRepository.save(renamed);
    }
}