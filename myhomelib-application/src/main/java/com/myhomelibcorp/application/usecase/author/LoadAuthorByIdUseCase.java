package com.myhomelibcorp.application.usecase.author;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LoadAuthorByIdUseCase {

    private final AuthorRepository authorRepository;
    private final AuthorMapper authorMapper;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/non-Spring embedding. */
    public LoadAuthorByIdUseCase(AuthorRepository authorRepository, AuthorMapper authorMapper) {
        this(authorRepository, authorMapper, null);
    }

    @Autowired
    public LoadAuthorByIdUseCase(AuthorRepository authorRepository, AuthorMapper authorMapper,
                                 LibraryOperationCoordinator operationCoordinator) {
        this.authorRepository = authorRepository;
        this.authorMapper = authorMapper;
        this.operationCoordinator = operationCoordinator;
    }

    public Optional<AuthorDto> execute(AuthorId authorId) {
        if (operationCoordinator == null) return load(authorId);
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            Optional<AuthorDto> result = load(authorId);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private Optional<AuthorDto> load(AuthorId authorId) {
        return authorRepository.findById(authorId).map(authorMapper::toDto);
    }
}