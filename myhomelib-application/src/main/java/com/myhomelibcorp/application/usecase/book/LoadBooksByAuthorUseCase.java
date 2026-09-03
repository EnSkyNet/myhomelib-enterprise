package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookQuickFilterField;
import com.myhomelibcorp.application.mapper.BookListItemMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalLong;

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
        BookFilterSpec filter = buildFilter(filterText);
        BookQuery query = buildQuery(
                authorId, filter,
                sortBy == null ? SortBy.TITLE : sortBy,
                direction == null ? SortDirection.ASC : direction,
                safeLimit, safeOffset);
        PageResult<com.myhomelibcorp.domain.model.book.Book> page = bookQueryRepository.findPage(query);
        List<BookListItem> content = page.content().stream().map(bookListItemMapper::toListItem).toList();
        return PageResult.of(content, page.totalElements(), page.currentPage(), page.size());
    }
    /**
     * Loads the complete author result through bounded SQL pages. The UI stays unpaged while
     * the repository still avoids one unbounded SELECT for authors with very large bibliographies.
     */
    public List<BookListItem> executeAll(AuthorId authorId, String filterText,
                                         SortBy sortBy, SortDirection direction) {
        if (authorId == null) return List.of();
        final int chunkSize = 200;
        final SortBy effectiveSort = sortBy == null ? SortBy.TITLE : sortBy;
        final SortDirection effectiveDirection = direction == null ? SortDirection.ASC : direction;
        final BookFilterSpec filter = buildFilter(filterText);

        int offset = 0;
        OptionalLong expectedTotal = OptionalLong.empty();
        BookPageCursor titleCursor = null;
        java.util.ArrayList<BookListItem> all = new java.util.ArrayList<>();

        while (expectedTotal.isEmpty() || offset < expectedTotal.getAsLong()) {
            BookQuery query = buildQuery(authorId, filter, effectiveSort, effectiveDirection, chunkSize, offset);
            PageResult<com.myhomelibcorp.domain.model.book.Book> page;
            if (expectedTotal.isEmpty()) {
                page = bookQueryRepository.findPage(query);
                expectedTotal = OptionalLong.of(page.totalElements());
            } else if (effectiveSort == SortBy.TITLE && titleCursor != null) {
                page = bookQueryRepository.findTitlePageByCursor(
                        query, titleCursor, BookPageDirection.AFTER, expectedTotal.getAsLong());
            } else {
                page = bookQueryRepository.findPage(query, expectedTotal.getAsLong());
            }

            if (page.content().isEmpty()) break;
            List<BookListItem> mapped = page.content().stream().map(bookListItemMapper::toListItem).toList();
            all.addAll(mapped);
            offset += mapped.size();

            if (effectiveSort == SortBy.TITLE) {
                com.myhomelibcorp.domain.model.book.Book last = page.content().get(page.content().size() - 1);
                titleCursor = new BookPageCursor(last.getTitle(), last.getId().asString());
            }
            if (!page.hasNext()) break;
        }
        return List.copyOf(all);
    }

    private BookFilterSpec buildFilter(String filterText) {
        BookFilterSpec filter = BookFilterSpec.empty();
        if (filterText != null && !filterText.isBlank()) {
            filter = filter.withQuickFilter(BookQuickFilterField.ANY, filterText);
        }
        return filter;
    }

    private BookQuery buildQuery(AuthorId authorId, BookFilterSpec filter,
                                 SortBy sortBy, SortDirection direction, int limit, int offset) {
        return BookQuery.builder()
                .authorId(authorId)
                .filterSpec(filter)
                .pagination(Pagination.of(limit, offset))
                .sortBy(sortBy)
                .direction(direction)
                .build();
    }

}
