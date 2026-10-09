package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MigrationStartupTaskTest {
    @Test
    void migratesWithoutAutomaticRebuildAndPublishesAuthoritativeCollection() {
        SwitchCollectionUseCase switchUseCase = mock(SwitchCollectionUseCase.class);
        ApplicationState state = mock(ApplicationState.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        MigrationStartupTask task = new MigrationStartupTask(switchUseCase, state, lifecycle);
        var requested = StartupTestFixtures.collection("c1");
        var authoritative = StartupTestFixtures.collection("c1-authoritative");
        when(switchUseCase.executeWithStatus(requested, false))
                .thenReturn(new SwitchCollectionUseCase.SwitchResult(authoritative, false));
        StartupContext context = new StartupContext(requested);

        StartupTaskResult result = task.execute(context);

        assertThat(result.executed()).isTrue();
        assertThat(context.activeCollection()).isSameAs(authoritative);
        assertThat(context.reusableSearchIndex()).isFalse();
        verify(switchUseCase).executeWithStatus(requested, false);
        verify(state).setCurrentLibraryCollection(authoritative);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void postSwitchFailureClosesCollectionBeforeRequiredFailureEscapes() {
        SwitchCollectionUseCase switchUseCase = mock(SwitchCollectionUseCase.class);
        ApplicationState state = mock(ApplicationState.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        MigrationStartupTask task = new MigrationStartupTask(switchUseCase, state, lifecycle);
        var collection = StartupTestFixtures.collection("c1");
        when(switchUseCase.executeWithStatus(collection, false))
                .thenReturn(new SwitchCollectionUseCase.SwitchResult(collection, true));
        doThrow(new IllegalStateException("state publication failed"))
                .when(state).setCurrentLibraryCollection(collection);

        assertThatThrownBy(() -> task.execute(new StartupContext(collection)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("state publication failed");
        verify(lifecycle).closeCollection();
    }
}
