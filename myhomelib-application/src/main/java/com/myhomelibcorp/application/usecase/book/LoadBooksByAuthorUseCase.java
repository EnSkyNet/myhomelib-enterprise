package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.mapper.BookListItemMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LoadBooksByAuthorUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookListItemMapper bookListItemMapper;

    public List<BookListItem> execute(AuthorId authorId, int limit, int offset) {
        BookQuery query = BookQuery.builder()
                .authorId(authorId)
                .pagination(Pagination.of(limit, offset))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        return bookQueryRepository.findPage(query).content().stream()
                .map(bookListItemMapper::toListItem)
                .collect(Collectors.toList());
    }

    public long count(AuthorId authorId) {
        BookQuery query = BookQuery.builder()
                .authorId(authorId)
                .pagination(Pagination.of(1, 0))
                .build();
        return bookQueryRepository.count(query);
    }
}