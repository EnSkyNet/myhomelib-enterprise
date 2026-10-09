package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LoadBookByIdUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/non-Spring embedding. */
    public LoadBookByIdUseCase(BookQueryRepository bookQueryRepository, BookMapper bookMapper) {
        this(bookQueryRepository, bookMapper, null);
    }

    @Autowired
    public LoadBookByIdUseCase(BookQueryRepository bookQueryRepository, BookMapper bookMapper,
                               LibraryOperationCoordinator operationCoordinator) {
        this.bookQueryRepository = bookQueryRepository;
        this.bookMapper = bookMapper;
        this.operationCoordinator = operationCoordinator;
    }

    public Optional<BookDto> execute(BookId bookId) {
        if (operationCoordinator == null) return load(bookId);
        return operationCoordinator.executeDetachedShared(
                LibraryOperationType.QUERY, () -> load(bookId));
    }

    private Optional<BookDto> load(BookId bookId) {
        return bookQueryRepository.findById(bookId).map(bookMapper::toDto);
    }
}