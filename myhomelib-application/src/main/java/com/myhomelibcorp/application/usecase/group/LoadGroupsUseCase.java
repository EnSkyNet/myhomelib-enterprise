package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.domain.model.group.Group;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadGroupsUseCase {
    private final GroupRepository groupRepository;

    public List<Group> execute() {
        return groupRepository.findAll();
    }
}