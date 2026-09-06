package com.myhomelibcorp.startup;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StartupOrchestratorTest {
    @Test
    void orderIsExplicitAndBestEffortFailureDoesNotHideRequiredProgress() throws Exception {
        StartupCollectionResolver resolver = mock(StartupCollectionResolver.class);
        RecoveryStartupTask recovery = mock(RecoveryStartupTask.class);
        MigrationStartupTask migration = mock(MigrationStartupTask.class);
        SearchStartupTask search = mock(SearchStartupTask.class);
        BackupStartupTask backup = mock(BackupStartupTask.class);
        OPDSStartupTask opds = mock(OPDSStartupTask.class);
        var collection = StartupTestFixtures.collection("c1");
        when(resolver.resolve()).thenReturn(collection);
        stub(recovery, "RecoveryStartupTask", StartupFailurePolicy.REQUIRED, StartupTaskResult.success("ok"));
        stub(migration, "MigrationStartupTask", StartupFailurePolicy.REQUIRED, StartupTaskResult.success("ok"));
        when(search.id()).thenReturn("SearchStartupTask");
        when(search.failurePolicy()).thenReturn(StartupFailurePolicy.BEST_EFFORT);
        when(search.execute(any())).thenThrow(new IllegalStateException("search executor saturated"));
        stub(backup, "BackupStartupTask", StartupFailurePolicy.BEST_EFFORT, StartupTaskResult.skipped("none"));
        stub(opds, "OPDSStartupTask", StartupFailurePolicy.BEST_EFFORT, StartupTaskResult.skipped("disabled"));
        StartupOrchestrator orchestrator = new StartupOrchestrator(resolver, recovery, migration, search, backup, opds);

        StartupReport report = orchestrator.run();

        assertThat(orchestrator.orderedTaskIds()).containsExactly(
                "RecoveryStartupTask", "MigrationStartupTask", "SearchStartupTask", "BackupStartupTask", "OPDSStartupTask");
        assertThat(report.degraded()).isTrue();
        assertThat(report.outcomes()).extracting(StartupTaskOutcome::status).containsExactly(
                StartupTaskOutcome.Status.SUCCESS,
                StartupTaskOutcome.Status.SUCCESS,
                StartupTaskOutcome.Status.DEGRADED,
                StartupTaskOutcome.Status.SKIPPED,
                StartupTaskOutcome.Status.SKIPPED);
        InOrder order = inOrder(recovery, migration, search, backup, opds);
        order.verify(recovery).execute(any());
        order.verify(migration).execute(any());
        order.verify(search).execute(any());
        order.verify(backup).execute(any());
        order.verify(opds).execute(any());
    }

    @Test
    void requiredFailureAbortsRemainingStartupTasksWithTaskIdentity() throws Exception {
        StartupCollectionResolver resolver = mock(StartupCollectionResolver.class);
        RecoveryStartupTask recovery = mock(RecoveryStartupTask.class);
        MigrationStartupTask migration = mock(MigrationStartupTask.class);
        SearchStartupTask search = mock(SearchStartupTask.class);
        BackupStartupTask backup = mock(BackupStartupTask.class);
        OPDSStartupTask opds = mock(OPDSStartupTask.class);
        when(resolver.resolve()).thenReturn(StartupTestFixtures.collection("c1"));
        when(recovery.id()).thenReturn("RecoveryStartupTask");
        when(recovery.failurePolicy()).thenReturn(StartupFailurePolicy.REQUIRED);
        when(recovery.execute(any())).thenThrow(new IllegalStateException("recovery marker is corrupt"));
        StartupOrchestrator orchestrator = new StartupOrchestrator(resolver, recovery, migration, search, backup, opds);

        assertThatThrownBy(orchestrator::run)
                .isInstanceOf(StartupException.class)
                .satisfies(error -> assertThat(((StartupException) error).taskId()).isEqualTo("RecoveryStartupTask"));
        verifyNoInteractions(migration, search, backup, opds);
    }

    private static void stub(StartupTask task, String id, StartupFailurePolicy policy, StartupTaskResult result) throws Exception {
        when(task.id()).thenReturn(id);
        when(task.failurePolicy()).thenReturn(policy);
        when(task.execute(any())).thenReturn(result);
    }
}
