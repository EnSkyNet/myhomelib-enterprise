package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.mapper.BookListItemMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadGroupBooksUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookListItemMapper bookListItemMapper;

    public PageResult<BookListItem> execute(Long groupId, int limit, int offset) {
        if (groupId == null) return PageResult.empty();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        BookQuery query = BookQuery.builder()
                .groupId(GroupId.fromLong(groupId))
                .pagination(Pagination.of(safeLimit, safeOffset))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        PageResult<com.myhomelibcorp.domain.model.book.Book> page = bookQueryRepository.findPage(query);
        List<BookListItem> content = page.content().stream()
                .map(bookListItemMapper::toListItem)
                .toList();
        return PageResult.of(content, page.totalElements(), page.currentPage(), page.size());
    }
}
