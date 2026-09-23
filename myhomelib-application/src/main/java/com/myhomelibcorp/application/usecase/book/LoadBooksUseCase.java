package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LoadBooksUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/non-Spring embedding. */
    public LoadBooksUseCase(BookQueryRepository bookQueryRepository, BookMapper bookMapper) {
        this(bookQueryRepository, bookMapper, null);
    }

    @Autowired
    public LoadBooksUseCase(BookQueryRepository bookQueryRepository, BookMapper bookMapper,
                            LibraryOperationCoordinator operationCoordinator) {
        this.bookQueryRepository = bookQueryRepository;
        this.bookMapper = bookMapper;
        this.operationCoordinator = operationCoordinator;
    }

    public PageResult<BookDto> execute(BookQuery query) {
        return query(() -> map(bookQueryRepository.findPage(query)));
    }

    public PageResult<BookDto> execute(BookQuery query, long knownTotal) {
        return query(() -> map(bookQueryRepository.findPage(query, knownTotal)));
    }

    public PageResult<BookDto> executeTitleCursor(BookQuery query, BookPageCursor cursor,
                                                   BookPageDirection pageDirection, long knownTotal) {
        return query(() -> map(bookQueryRepository.findTitlePageByCursor(query, cursor, pageDirection, knownTotal)));
    }

    private <T> T query(java.util.function.Supplier<T> action) {
        if (operationCoordinator == null) return action.get();
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            T result = action.get();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private PageResult<BookDto> map(PageResult<Book> result) {
        List<BookDto> dtos = result.content().stream().map(bookMapper::toDto).toList();
        return new PageResult<>(
                dtos,
                result.totalElements(),
                result.totalPages(),
                result.currentPage(),
                result.size()
        );
    }
}
