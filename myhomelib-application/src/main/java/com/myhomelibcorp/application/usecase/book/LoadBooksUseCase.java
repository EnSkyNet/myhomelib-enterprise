package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.BookFilter;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.BookQuery;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GroupRepository;
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

    public List<BookDto> execute(BookFilter filter) {
        if (filter == null) {
            return loadAll(1000, 0);
        }

        if (filter.isEmpty()) {
            int limit = filter.getLimit() != null ? filter.getLimit() : 1000;
            int offset = filter.getOffset() != null ? filter.getOffset() : 0;
            return loadAll(limit, offset);
        }

        // Групи – особливий випадок
        if (filter.getGroupId() != null) {
            return loadByGroup(filter);
        }

        // Уніфікований запит для всіх інших критеріїв
        BookQuery query = BookQuery.builder()
                .authorId(filter.getAuthorId())
                .seriesName(filter.getSeriesName())
                .genreCode(filter.getGenreCode())
                .searchText(filter.getSearchText())
                .limit(filter.getLimit() != null ? filter.getLimit() : 1000)
                .offset(filter.getOffset() != null ? filter.getOffset() : 0)
                .build();

        List<Book> books = bookQueryRepository.find(query);
        return books.stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }

    private List<BookDto> loadAll(int limit, int offset) {
        return bookQueryRepository.findAll(limit, offset).stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }

    private List<BookDto> loadByGroup(BookFilter filter) {
        List<String> bookIds = groupRepository.findBookIdsByGroup(filter.getGroupId());
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

    public int getTotalCount() {
        return bookQueryRepository.getTotalCount();
    }
}