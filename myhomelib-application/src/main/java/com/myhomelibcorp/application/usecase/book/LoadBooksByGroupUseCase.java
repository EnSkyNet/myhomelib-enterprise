package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GroupRepository;
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
    private final BookMapper bookMapper;

    public List<BookDto> execute(Long groupId) {
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
        return bookQueryRepository.findByIds(ids).stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }
}