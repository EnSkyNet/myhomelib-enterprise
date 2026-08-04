package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LoadGroupsUseCase {

    private final GroupRepository groupRepository;

    public List<GroupDto> execute() {
        return groupRepository.findAll().stream()
                .map(group -> new GroupDto(
                        group.getId().asLong(),
                        group.getName(),
                        group.isAllowDelete()
                ))
                .collect(Collectors.toList());
    }
}