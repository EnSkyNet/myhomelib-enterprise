package com.myhomelibcorp.application.group;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GroupRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LoadBooksByGroupUseCase {

    private final GroupRepository groupRepository;
    private final BookQueryRepository bookQueryRepository;

    public List<Book> loadByGroup(Long groupId) {
        if (groupId == null) {
            throw new IllegalArgumentException("Group ID cannot be null");
        }
        List<String> bookIds = groupRepository.findBookIdsByGroup(groupId);
        if (bookIds.isEmpty()) {
            return List.of();
        }
        List<BookId> ids = bookIds.stream()
                .map(BookId::fromString)
                .collect(Collectors.toList());
        return bookQueryRepository.findByIds(ids);
    }
}