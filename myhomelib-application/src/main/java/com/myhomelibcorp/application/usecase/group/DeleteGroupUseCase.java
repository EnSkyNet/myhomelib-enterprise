package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeleteGroupUseCase {

    private final GroupRepository groupRepository;

    public void execute(Long groupId) {
        if (groupId == null) {
            throw new IllegalArgumentException("Group ID cannot be null");
        }
        groupRepository.deleteById(groupId);
    }
}