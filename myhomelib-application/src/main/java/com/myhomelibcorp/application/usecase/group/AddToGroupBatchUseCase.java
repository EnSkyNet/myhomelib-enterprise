package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AddToGroupBatchUseCase {

    private final GroupRepository groupRepository;
    private final GroupMutationExecutor mutationExecutor;

    public void execute(Long groupId, List<BookId> bookIds) {
        execute(groupId, bookIds, null);
    }

    public void execute(Long groupId, List<BookId> bookIds, String expectedCollectionId) {
        if (groupId == null || bookIds == null || bookIds.isEmpty()) {
            return;
        }
        if (bookIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Book ids cannot contain null values");
        }
        List<String> stableIds = bookIds.stream().map(BookId::asString).toList();
        mutationExecutor.execute(expectedCollectionId, () -> groupRepository.addBooksToGroup(groupId, stableIds));
    }
}
