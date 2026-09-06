package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.service.CollectionLifecycleService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SearchStartupTaskTest {
    @Test
    void skipsRebuildForReusableIndex() {
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        SearchStartupTask task = new SearchStartupTask(lifecycle);
        StartupContext context = new StartupContext(StartupTestFixtures.collection("c1"));
        context.reusableSearchIndex(true);

        StartupTaskResult result = task.execute(context);

        assertThat(result.executed()).isFalse();
        verifyNoInteractions(lifecycle);
    }

    @Test
    void dirtyIndexIsScheduledInBackgroundAfterMigrationPhase() {
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        when(lifecycle.rebuildSearchIndexAsync()).thenReturn(CompletableFuture.completedFuture(null));
        SearchStartupTask task = new SearchStartupTask(lifecycle);
        StartupContext context = new StartupContext(StartupTestFixtures.collection("c1"));
        context.reusableSearchIndex(false);

        StartupTaskResult result = task.execute(context);

        assertThat(result.executed()).isTrue();
        verify(lifecycle).rebuildSearchIndexAsync();
        assertThat(task.failurePolicy()).isEqualTo(StartupFailurePolicy.BEST_EFFORT);
    }
}
