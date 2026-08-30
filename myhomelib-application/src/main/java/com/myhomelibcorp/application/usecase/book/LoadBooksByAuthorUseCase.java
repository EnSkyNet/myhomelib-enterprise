package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookQuickFilterField;
import com.myhomelibcorp.application.mapper.BookListItemMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadBooksByAuthorUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookListItemMapper bookListItemMapper;

    public PageResult<BookListItem> execute(AuthorId authorId, String filterText,
                                            SortBy sortBy, SortDirection direction,
                                            int limit, int offset) {
        if (authorId == null) return PageResult.empty();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        BookFilterSpec filter = BookFilterSpec.empty();
        if (filterText != null && !filterText.isBlank()) {
            filter = filter.withQuickFilter(BookQuickFilterField.ANY, filterText);
        }
        BookQuery query = BookQuery.builder()
                .authorId(authorId)
                .filterSpec(filter)
                .pagination(Pagination.of(safeLimit, safeOffset))
                .sortBy(sortBy == null ? SortBy.TITLE : sortBy)
                .direction(direction == null ? SortDirection.ASC : direction)
                .build();
        PageResult<com.myhomelibcorp.domain.model.book.Book> page = bookQueryRepository.findPage(query);
        List<BookListItem> content = page.content().stream().map(bookListItemMapper::toListItem).toList();
        return PageResult.of(content, page.totalElements(), page.currentPage(), page.size());
    }
}
