package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.mapper.BookListItemMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LoadGroupBooksUseCase {

    private final GroupRepository groupRepository;
    private final BookQueryRepository bookQueryRepository;
    private final BookListItemMapper bookListItemMapper;

    public List<BookListItem> execute(Long groupId) {
        List<String> bookIds = groupRepository.findBookIdsByGroup(groupId);
        if (bookIds.isEmpty()) {
            return List.of();
        }
        List<BookId> ids = bookIds.stream()
                .map(BookId::fromString)
                .collect(Collectors.toList());
        List<Book> books = bookQueryRepository.findByIds(ids);
        return books.stream()
                .map(bookListItemMapper::toListItem)
                .collect(Collectors.toList());
    }
}