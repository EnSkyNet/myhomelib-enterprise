package com.myhomelibcorp.application.folderwatch;

import com.myhomelibcorp.application.event.IncomingFolderFileReadyEvent;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.context.StaleImportSourceException;
import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.collection.IncomingFolderWatchPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.event.collection.CollectionOpenedEvent;
import com.myhomelibcorp.shared.util.ThrowableMessages;
import com.myhomelibcorp.shared.util.ThrowableText;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MHL-113 background importer. Watchers may run for every configured collection, but writes are
 * performed only while the candidate's collection is active. READY rows remain durable otherwise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IncomingFolderImportCoordinator {
    private static final int DRAIN_BATCH = 25;

    private final IncomingFolderWatchPort watcher;
    private final ImportFileUseCase importer;
    private final CollectionLifecyclePort collections;
    private final ExecutorPort executor;
    private final Set<String> draining = ConcurrentHashMap.newKeySet();
    // A READY notification arriving just after the drain's final empty SELECT used to be
    // dropped while the previous worker still held the draining flag. Retain a wake-up
    // bit per collection so the current worker always performs one more pass.
    private final Map<String, AtomicBoolean> pendingDrains = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();

    @EventListener(ContextRefreshedEvent.class)
    void resumeActiveCollection() {
        var current = collections.getCurrentCollection();
        if (current != null) schedule(current.getId());
    }

    @EventListener
    public void onReady(IncomingFolderFileReadyEvent event) {
        if (event != null) schedule(event.collectionId());
    }

    @EventListener
    public void onCollectionOpened(CollectionOpenedEvent event) {
        if (event != null) schedule(event.getCollectionId());
    }

    public void schedule(String collectionId) {
        if (stopping.get() || collectionId == null || collectionId.isBlank()) return;
        AtomicBoolean pending = pendingDrains.computeIfAbsent(collectionId, ignored -> new AtomicBoolean());
        pending.set(true);
        if (!draining.add(collectionId)) return;
        try {
            executor.execute(() -> {
                try {
                    do {
                        pending.set(false);
                        drain(collectionId);
                    } while (!stopping.get() && pending.get() && activeCollectionIs(collectionId));
                } finally {
                    draining.remove(collectionId);
                    // If a wake-up raced with the final test above, hand it to a fresh
                    // worker. AtomicBoolean is retained to avoid an ABA/removal race.
                    if (!stopping.get() && pending.get() && activeCollectionIs(collectionId)) {
                        schedule(collectionId);
                    }
                }
            });
        } catch (RuntimeException | Error rejection) {
            // If ExecutorPort rejected submission, do not permanently block this collection.
            draining.remove(collectionId);
            throw rejection;
        }
    }

    private void drain(String collectionId) {
        while (!stopping.get() && activeCollectionIs(collectionId)) {
            List<IncomingFolderCandidate> ready = watcher.listReady(collectionId, DRAIN_BATCH);
            if (ready.isEmpty()) return;
            boolean claimedAny = false;
            for (IncomingFolderCandidate candidate : ready) {
                if (stopping.get() || !activeCollectionIs(collectionId)) return;
                if (candidate == null || candidate.file() == null || candidate.fingerprint() == null) continue;
                if (!watcher.claimReady(collectionId, candidate.file(), candidate.fingerprint())) continue;
                claimedAny = true;
                if (!importOne(collectionId, candidate)) return;
            }
            if (!claimedAny) return;
        }
    }

    private boolean importOne(String collectionId, IncomingFolderCandidate candidate) {
        Path folder = watcher.findState(collectionId).map(IncomingFolderWatchState::folder)
                .orElse(candidate.file().getParent());
        try {
            var result = importer.execute(ImportContext.builder()
                    .file(candidate.file())
                    .rootDirectory(folder)
                    .expectedCollectionId(collectionId)
                    .expectedSourceSha256(candidate.fingerprint())
                    .updateExisting(false)
                    .indexAfterSave(true)
                    .publishFinishedEvent(true)
                    .cancelFlag(stopping)
                    .operationId("folder-watch-" + collectionId + "-" + candidate.fingerprint().substring(0, 12))
                    .build());
            if (result.status() == ImportStatus.CANCELLED) {
                watcher.markFailed(collectionId, candidate.file(), candidate.fingerprint(), "Import cancelled");
                return true;
            }
            if (result.errors() > 0) {
                watcher.markFailed(collectionId, candidate.file(), candidate.fingerprint(),
                        "Import completed with " + result.errors() + " error(s)");
                return true;
            }
            watcher.markImported(collectionId, candidate.file(), candidate.fingerprint(),
                    result.imported(), result.duplicates(), result.errors());
            return true;
        } catch (Exception failure) {
            if (isRetryableLifecycleFailure(failure)) {
                // The candidate was claimed before ImportFileUseCase could acquire its lifecycle lease.
                // Put it back so CollectionOpenedEvent can resume it when this collection is active again.
                watcher.releaseClaim(collectionId, candidate.file(), candidate.fingerprint());
                log.debug("Incoming-folder import deferred for {} because collection lifecycle changed", candidate.file());
                // Stop this drain pass. Retrying immediately can reclaim the same READY row in a tight loop
                // while the lifecycle transition is still in progress. CollectionOpenedEvent will reschedule it.
                return false;
            }
            if (isStaleSourceFailure(failure)) {
                // Do not import bytes that differ from the persisted READY fingerprint. Mark the old
                // claim non-ready first, then force a fresh stability/fingerprint observation.
                watcher.markFailed(collectionId, candidate.file(), candidate.fingerprint(), safeMessage(failure));
                try { watcher.scanNow(collectionId); }
                catch (RuntimeException rescanFailure) {
                    log.warn("Cannot rescan changed incoming file {}: {}", candidate.file(), safeMessage(rescanFailure));
                }
                log.debug("Incoming-folder source changed before import; revalidation scheduled for {}", candidate.file());
                return true;
            }
            watcher.markFailed(collectionId, candidate.file(), candidate.fingerprint(), safeMessage(failure));
            log.warn("Incoming-folder import failed for {}: {}", candidate.file(), safeMessage(failure));
            return true;
        }
    }

    private static boolean isRetryableLifecycleFailure(Throwable failure) {
        return ThrowableMessages.anyCause(failure, cause ->
                cause instanceof LibraryOperationConflictException
                        || cause instanceof ImportFileUseCase.StaleImportCollectionException);
    }

    private static boolean isStaleSourceFailure(Throwable failure) {
        return ThrowableMessages.anyCause(failure, StaleImportSourceException.class::isInstance);
    }

    private boolean activeCollectionIs(String collectionId) {
        var current = collections.getCurrentCollection();
        return current != null && current.getId() != null && current.getId().equals(collectionId);
    }

    private static String safeMessage(Throwable failure) {
        return ThrowableText.rootMessage(failure, "unknown error");
    }

    @EventListener(ContextClosedEvent.class)
    void shutdown() { stopping.set(true); }
}
