package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class IsBookInGroupUseCase {

    private final GroupRepository groupRepository;

    public boolean execute(Long groupId, String bookId) {
        List<String> bookIds = groupRepository.findBookIdsByGroup(groupId);
        return bookIds.contains(bookId);
    }
}