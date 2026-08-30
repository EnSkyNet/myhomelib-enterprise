package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IsBookInGroupUseCase {

    private final GroupRepository groupRepository;

    public boolean execute(Long groupId, String bookId) {
        if (groupId == null || bookId == null || bookId.isBlank()) return false;
        return groupRepository.containsBook(groupId, bookId);
    }
}
