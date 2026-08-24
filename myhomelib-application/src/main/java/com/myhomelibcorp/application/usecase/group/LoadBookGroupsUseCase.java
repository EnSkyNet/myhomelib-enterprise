package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadBookGroupsUseCase {
    private final GroupRepository groupRepository;

    public List<GroupDto> execute(String bookId) {
        if (bookId == null || bookId.isBlank()) return List.of();
        return groupRepository.findByBookId(bookId).stream()
                .map(group -> new GroupDto(group.getId().asLong(), group.getName(), group.isAllowDelete()))
                .toList();
    }
}
