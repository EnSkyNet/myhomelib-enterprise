package com.myhomelibcorp.startup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit desktop startup sequence. Constructor order is deliberate and is guarded by tests/static CI.
 *
 * <ol>
 *   <li>Recovery before any SQLite open.</li>
 *   <li>Collection activation + Flyway migration.</li>
 *   <li>Search reuse/rebuild policy after migration.</li>
 *   <li>Backup staging cleanup.</li>
 *   <li>Optional OPDS autostart.</li>
 * </ol>
 */
@Component
@Slf4j
public class StartupOrchestrator {
    private final StartupCollectionResolver collectionResolver;
    private final List<StartupTask> tasks;

    public StartupOrchestrator(
            StartupCollectionResolver collectionResolver,
            RecoveryStartupTask recoveryStartupTask,
            MigrationStartupTask migrationStartupTask,
            SearchStartupTask searchStartupTask,
            BackupStartupTask backupStartupTask,
            OPDSStartupTask opdsStartupTask) {
        this.collectionResolver = collectionResolver;
        this.tasks = List.of(
                recoveryStartupTask,
                migrationStartupTask,
                searchStartupTask,
                backupStartupTask,
                opdsStartupTask);
    }

    public StartupReport run() {
        StartupContext context = new StartupContext(collectionResolver.resolve());
        List<StartupTaskOutcome> outcomes = new ArrayList<>(tasks.size());

        for (StartupTask task : tasks) {
            long started = System.nanoTime();
            try {
                StartupTaskResult result = task.execute(context);
                outcomes.add(new StartupTaskOutcome(
                        task.id(), task.failurePolicy(),
                        result.executed() ? StartupTaskOutcome.Status.SUCCESS : StartupTaskOutcome.Status.SKIPPED,
                        elapsedMillis(started), result.detail()));
                log.info("Startup task {} {}: {}", task.id(), result.executed() ? "completed" : "skipped", result.detail());
            } catch (Exception failure) {
                long duration = elapsedMillis(started);
                if (task.failurePolicy() == StartupFailurePolicy.REQUIRED) {
                    log.error("Required startup task {} failed after {} ms", task.id(), duration, failure);
                    throw new StartupException(task.id(), failure);
                }
                String detail = rootMessage(failure);
                outcomes.add(new StartupTaskOutcome(
                        task.id(), task.failurePolicy(), StartupTaskOutcome.Status.DEGRADED, duration, detail));
                log.warn("Best-effort startup task {} failed; startup continues in degraded mode: {}",
                        task.id(), detail, failure);
            }
        }

        return new StartupReport(context.activeCollection(), outcomes);
    }

    public List<String> orderedTaskIds() {
        return tasks.stream().map(StartupTask::id).toList();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
