package com.myhomelibcorp.application.folderwatch;

import com.myhomelibcorp.application.event.IncomingFolderFileReadyEvent;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.collection.IncomingFolderWatchPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.event.collection.CollectionOpenedEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
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
        if (!draining.add(collectionId)) return;
        executor.execute(() -> {
            try { drain(collectionId); }
            finally { draining.remove(collectionId); }
        });
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
                importOne(collectionId, candidate);
            }
            if (!claimedAny) return;
        }
    }

    private void importOne(String collectionId, IncomingFolderCandidate candidate) {
        Path folder = watcher.findState(collectionId).map(IncomingFolderWatchState::folder)
                .orElse(candidate.file().getParent());
        try {
            var result = importer.execute(ImportContext.builder()
                    .file(candidate.file())
                    .rootDirectory(folder)
                    .updateExisting(false)
                    .indexAfterSave(true)
                    .publishFinishedEvent(true)
                    .cancelFlag(stopping)
                    .operationId("folder-watch-" + collectionId + "-" + candidate.fingerprint().substring(0, 12))
                    .build());
            if (result.status() == ImportStatus.CANCELLED) {
                watcher.markFailed(collectionId, candidate.file(), candidate.fingerprint(), "Import cancelled");
                return;
            }
            if (result.errors() > 0) {
                watcher.markFailed(collectionId, candidate.file(), candidate.fingerprint(),
                        "Import completed with " + result.errors() + " error(s)");
                return;
            }
            watcher.markImported(collectionId, candidate.file(), candidate.fingerprint(),
                    result.imported(), result.duplicates(), result.errors());
        } catch (Exception failure) {
            watcher.markFailed(collectionId, candidate.file(), candidate.fingerprint(), safeMessage(failure));
            log.warn("Incoming-folder import failed for {}: {}", candidate.file(), safeMessage(failure));
        }
    }

    private boolean activeCollectionIs(String collectionId) {
        var current = collections.getCurrentCollection();
        return current != null && current.getId() != null && current.getId().equals(collectionId);
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) return "unknown error";
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @EventListener(ContextClosedEvent.class)
    void shutdown() { stopping.set(true); }
}
