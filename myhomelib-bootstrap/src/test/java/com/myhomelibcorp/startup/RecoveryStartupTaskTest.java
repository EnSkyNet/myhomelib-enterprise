package com.myhomelibcorp.startup;

import com.myhomelibcorp.infrastructure.collection.CollectionStartupRecoveryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RecoveryStartupTaskTest {
    @Test
    void executesRecoveryBeforeOpenAndIsRequired() {
        CollectionStartupRecoveryService recovery = mock(CollectionStartupRecoveryService.class);
        RecoveryStartupTask task = new RecoveryStartupTask(recovery);
        StartupContext context = new StartupContext(StartupTestFixtures.collection("c1"));

        StartupTaskResult result = task.execute(context);

        verify(recovery).recoverBeforeOpen(context.activeCollection());
        assertThat(result.executed()).isTrue();
        assertThat(task.failurePolicy()).isEqualTo(StartupFailurePolicy.REQUIRED);
    }
}
