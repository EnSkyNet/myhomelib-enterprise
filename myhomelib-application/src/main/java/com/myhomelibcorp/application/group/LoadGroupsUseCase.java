package com.myhomelibcorp.application.group;

import com.myhomelibcorp.application.port.out.GroupRepository;
import com.myhomelibcorp.domain.model.group.Group;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadGroupsUseCase {

    private final GroupRepository groupRepository;

    public List<Group> loadAllGroups() {
        return groupRepository.findAll();
    }
}