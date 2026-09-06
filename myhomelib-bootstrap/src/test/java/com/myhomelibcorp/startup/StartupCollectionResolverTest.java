package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StartupCollectionResolverTest {
    @Test
    void restoreEnabledSelectsLastCollectionWhenItStillExists() {
        CollectionRepository repository = mock(CollectionRepository.class);
        SessionService session = mock(SessionService.class);
        Collection first = StartupTestFixtures.collection("first");
        Collection last = StartupTestFixtures.collection("last");
        when(repository.findAll()).thenReturn(List.of(first, last));
        when(session.isRestoreEnabled()).thenReturn(true);
        when(session.getLastCollectionId()).thenReturn("last");

        assertThat(new StartupCollectionResolver(repository, session).resolve()).isSameAs(last);
    }

    @Test
    void emptyMetadataCreatesDefaultCollection() {
        CollectionRepository repository = mock(CollectionRepository.class);
        SessionService session = mock(SessionService.class);
        when(repository.findAll()).thenReturn(List.of());
        Collection saved = StartupTestFixtures.collection("generated");
        when(repository.save(any())).thenReturn(saved);

        assertThat(new StartupCollectionResolver(repository, session).resolve()).isSameAs(saved);
        verify(repository).save(any(Collection.class));
        verifyNoInteractions(session);
    }
}
