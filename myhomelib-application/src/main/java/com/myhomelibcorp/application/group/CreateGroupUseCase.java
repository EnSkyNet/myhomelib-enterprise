package com.myhomelibcorp.application.group;

import com.myhomelibcorp.application.port.out.GroupRepository;
import com.myhomelibcorp.domain.model.group.Group;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateGroupUseCase {

    private final GroupRepository groupRepository;

    public Group execute(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("Group name cannot be empty");
        }
        Group group = new Group(groupName, true);
        return groupRepository.save(group);
    }
}