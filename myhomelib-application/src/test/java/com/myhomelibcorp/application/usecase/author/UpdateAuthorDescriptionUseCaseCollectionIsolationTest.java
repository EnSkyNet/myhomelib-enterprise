package com.myhomelibcorp.application.usecase.author;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UpdateAuthorDescriptionUseCaseCollectionIsolationTest {
    @Test
    void staleCollectionIsRejectedBeforeRepositoryOrTransaction() {
        AuthorRepository authors = mock(AuthorRepository.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("b");
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        var useCase = new UpdateAuthorDescriptionUseCase(
                authors, new LibraryOperationCoordinator(), lifecycle, transaction);

        assertThrows(IllegalStateException.class,
                () -> useCase.execute(AuthorId.fromString("00000000-0000-0000-0000-000000000001"), "text", "a"));

        verifyNoInteractions(authors, transaction);
    }
}
