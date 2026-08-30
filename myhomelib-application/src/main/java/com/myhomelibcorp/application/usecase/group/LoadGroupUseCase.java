package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.domain.model.group.Group;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoadGroupUseCase {
    private final GroupRepository groupRepository;

    public Optional<Group> execute(Long groupId) {
        return groupId == null ? Optional.empty() : groupRepository.findById(groupId);
    }
}
