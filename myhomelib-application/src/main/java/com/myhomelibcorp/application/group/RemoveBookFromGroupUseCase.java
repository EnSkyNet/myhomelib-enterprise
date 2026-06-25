package com.myhomelibcorp.application.group;

import com.myhomelibcorp.application.port.out.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RemoveBookFromGroupUseCase {

    private final GroupRepository groupRepository;

    public void execute(Long groupId, String bookId) {
        if (groupId == null || bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("Group ID and Book ID cannot be null");
        }
        groupRepository.removeBookFromGroup(groupId, bookId);
    }
}