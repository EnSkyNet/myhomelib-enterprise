package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GroupRepository;
import com.myhomelibcorp.application.query.BookQuery;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoadBooksUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final GroupRepository groupRepository;
    private final BookMapper bookMapper;

    public List<BookDto> execute(BookQuery query) {
        if (query.groupId() != null) {
            return loadByGroup(query);
        }
        List<Book> books = bookQueryRepository.find(query);
        return books.stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }

    private List<BookDto> loadByGroup(BookQuery query) {
        List<String> bookIds = groupRepository.findBookIdsByGroup(query.groupId().asLong());
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

    public long count(BookQuery query) {
        return bookQueryRepository.count(query);
    }
}