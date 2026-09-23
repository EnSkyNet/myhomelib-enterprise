package com.myhomelibcorp.application.usecase.author;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import org.springframework.transaction.support.TransactionTemplate;

public class UpdateAuthorDescriptionUseCase {
    private final AuthorRepository authors;
    private final LibraryOperationCoordinator operations;
    private final CollectionLifecyclePort lifecycle;
    private final TransactionTemplate transaction;

    /** Compatibility constructor for focused unit tests. Production wiring uses the guarded constructor. */
    public UpdateAuthorDescriptionUseCase(AuthorRepository authors) {
        this(authors, null, null, null);
    }

    public UpdateAuthorDescriptionUseCase(AuthorRepository authors,
                                          LibraryOperationCoordinator operations,
                                          CollectionLifecyclePort lifecycle,
                                          TransactionTemplate transaction) {
        this.authors = authors;
        this.operations = operations;
        this.lifecycle = lifecycle;
        this.transaction = transaction;
    }

    public Author execute(AuthorId id, String annotation) {
        return execute(id, annotation, null);
    }

    public Author execute(AuthorId id, String annotation, String expectedCollectionId) {
        if (id == null) throw new IllegalArgumentException("AuthorId cannot be null");
        if (operations == null || transaction == null || lifecycle == null) {
            return updateLocked(id, annotation);
        }

        var lease = operations.acquireDetachedShared(LibraryOperationType.EDIT);
        try {
            assertExpectedCollection(expectedCollectionId);
            Author updated = transaction.execute(status -> updateLocked(id, annotation));
            lease.markCompleted("");
            return updated;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private Author updateLocked(AuthorId id, String annotation) {
        Author author = authors.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Автор не знайдений"));
        author.updateAnnotation(annotation);
        return authors.save(author);
    }

    private void assertExpectedCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) return;
        Collection active = lifecycle.getCurrentCollection();
        if (active == null || active.getId() == null
                || !expectedCollectionId.trim().equals(active.getId().trim())) {
            throw new IllegalStateException("Активна колекція змінилася до редагування автора");
        }
    }
}
