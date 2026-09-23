package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.content.indexing.ContentIndexingQueueService;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.collection.CollectionSourceMonitorPort;
import com.myhomelibcorp.application.port.out.collection.IncomingFolderWatchPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionDatabaseOwnershipPort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DeleteCollectionUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionStorageManager storageManager;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final CollectionSourceMonitorPort sourceMonitorPort;
    private static final long CONTENT_INDEX_DELETE_QUIESCE_MILLIS = 5_000L;

    private final IncomingFolderWatchPort incomingFolderWatchPort;
    private final LibraryOperationCoordinator operationCoordinator;
    private final ContentIndexingQueueService contentIndexingQueue;
    private final CollectionDatabaseOwnershipPort databaseOwnership;

    /** Compatibility constructor for isolated tests created before ownership metadata. */
    public DeleteCollectionUseCase(CollectionRepository collectionRepository,
                                   CollectionStorageManager storageManager,
                                   CollectionLifecyclePort collectionLifecyclePort,
                                   CollectionSourceMonitorPort sourceMonitorPort,
                                   IncomingFolderWatchPort incomingFolderWatchPort,
                                   LibraryOperationCoordinator operationCoordinator) {
        this(collectionRepository, storageManager, collectionLifecyclePort, sourceMonitorPort, incomingFolderWatchPort,
                operationCoordinator, null, null);
    }

    /** Compatibility constructor for tests that exercise the full-text queue but not ownership metadata. */
    public DeleteCollectionUseCase(CollectionRepository collectionRepository,
                                   CollectionStorageManager storageManager,
                                   CollectionLifecyclePort collectionLifecyclePort,
                                   CollectionSourceMonitorPort sourceMonitorPort,
                                   IncomingFolderWatchPort incomingFolderWatchPort,
                                   LibraryOperationCoordinator operationCoordinator,
                                   ContentIndexingQueueService contentIndexingQueue) {
        this(collectionRepository, storageManager, collectionLifecyclePort, sourceMonitorPort, incomingFolderWatchPort,
                operationCoordinator, contentIndexingQueue, null);
    }

    @Autowired
    public DeleteCollectionUseCase(CollectionRepository collectionRepository,
                                   CollectionStorageManager storageManager,
                                   CollectionLifecyclePort collectionLifecyclePort,
                                   CollectionSourceMonitorPort sourceMonitorPort,
                                   IncomingFolderWatchPort incomingFolderWatchPort,
                                   LibraryOperationCoordinator operationCoordinator,
                                   ContentIndexingQueueService contentIndexingQueue,
                                   CollectionDatabaseOwnershipPort databaseOwnership) {
        this.collectionRepository = collectionRepository;
        this.storageManager = storageManager;
        this.collectionLifecyclePort = collectionLifecyclePort;
        this.sourceMonitorPort = sourceMonitorPort;
        this.incomingFolderWatchPort = incomingFolderWatchPort;
        this.operationCoordinator = operationCoordinator;
        this.contentIndexingQueue = contentIndexingQueue;
        this.databaseOwnership = databaseOwnership;
    }

    public void execute(String id) {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.DELETE)) {
            executeLocked(id);
        }
    }

    private void executeLocked(String id) {
        Collection collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Колекцію не знайдено: " + id));

        Collection active = collectionLifecyclePort.getCurrentCollection();
        if (active != null && active.getId().equals(collection.getId())) {
            throw new IllegalStateException("Активну колекцію не можна видалити. Спочатку перемкніться на іншу.");
        }
        if (collectionRepository.findAll().size() <= 1) {
            throw new IllegalStateException("Не можна видалити останню колекцію.");
        }

        if (contentIndexingQueue != null
                && !contentIndexingQueue.discardCollection(id, CONTENT_INDEX_DELETE_QUIESCE_MILLIS)) {
            throw new IllegalStateException("Не вдалося зупинити full-text indexing перед видаленням колекції");
        }

        // Stop in-process source monitoring as part of the collection lifecycle, not as a UI side effect.
        sourceMonitorPort.stopMonitoring(id);
        incomingFolderWatchPort.stopMonitoring(id);
        storageManager.closeCollection(collection);

        CollectionDatabaseOwnershipPort.Ownership ownership = databaseOwnership == null
                ? CollectionDatabaseOwnershipPort.Ownership.OWNED
                : databaseOwnership.findOwnership(id);

        if (ownership == CollectionDatabaseOwnershipPort.Ownership.OWNED) {
            // Destructive DB removal is a two-phase filesystem/registry protocol. The database is
            // first detached under a durable recovery marker while the registry record still exists.
            storageManager.preparePhysicalDeletion(collection);
            try {
                collectionRepository.deleteById(id);
            } catch (RuntimeException registryFailure) {
                try {
                    storageManager.rollbackPreparedPhysicalDeletion(collection);
                } catch (RuntimeException rollbackFailure) {
                    registryFailure.addSuppressed(rollbackFailure);
                }
                throw registryFailure;
            }
            storageManager.commitPreparedPhysicalDeletion(collection);
        } else {
            // Attached and UNKNOWN-provenance SQLite files are user data. Deleting the collection
            // removes only registry metadata and rebuildable per-collection artifacts.
            collectionRepository.deleteById(id);
            storageManager.deleteDerivedFiles(collection);
            log.info("SQLite файл збережено під час видалення колекції {} (ownership={})", id, ownership);
        }
        log.info("Колекцію видалено: {}", id);
    }
}