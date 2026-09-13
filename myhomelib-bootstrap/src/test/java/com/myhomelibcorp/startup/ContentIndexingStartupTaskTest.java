package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.content.indexing.ContentIndexingQueueService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContentIndexingStartupTaskTest {
    @Test
    void restoresActiveCollectionCheckpoint() {
        ContentIndexingQueueService queue = mock(ContentIndexingQueueService.class);
        when(queue.restore("c1")).thenReturn(3);
        ContentIndexingStartupTask task = new ContentIndexingStartupTask(queue);

        StartupTaskResult result = task.execute(new StartupContext(StartupTestFixtures.collection("c1")));

        assertThat(result.executed()).isTrue();
        assertThat(result.detail()).contains("3");
        verify(queue).restore("c1");
    }

    @Test
    void noCheckpointIsReportedAsSkipped() {
        ContentIndexingQueueService queue = mock(ContentIndexingQueueService.class);
        ContentIndexingStartupTask task = new ContentIndexingStartupTask(queue);
        StartupTaskResult result = task.execute(new StartupContext(StartupTestFixtures.collection("c1")));
        assertThat(result.executed()).isFalse();
    }
}
