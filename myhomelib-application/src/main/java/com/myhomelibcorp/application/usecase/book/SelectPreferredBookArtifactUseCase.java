package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Changes the preferred representation and returns the refreshed logical book. */
@Service
@RequiredArgsConstructor
public class SelectPreferredBookArtifactUseCase {
    private final BookCommandRepository commands;
    private final BookQueryRepository queries;
    private final BookMapper mapper;
    private final LibraryOperationCoordinator operationCoordinator;

    public BookDto execute(BookId bookId, String artifactId) {
        if (bookId == null) throw new IllegalArgumentException("Book id is required");
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EDIT);
        try {
            commands.selectPreferredArtifact(bookId, artifactId);
            BookDto result = queries.findById(bookId)
                    .map(mapper::toDto)
                    .orElseThrow(() -> new IllegalStateException("Book not found after artifact selection: " + bookId));
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }
}
