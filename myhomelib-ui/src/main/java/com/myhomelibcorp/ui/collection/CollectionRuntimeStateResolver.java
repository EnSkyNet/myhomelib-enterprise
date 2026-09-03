package com.myhomelibcorp.ui.collection;

import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.operation.OperationCenterEntry;
import com.myhomelibcorp.ui.operation.OperationKind;

import java.util.Comparator;
import java.util.List;

/**
 * Derives collection runtime state exclusively from Operation Center entries.
 * No second mutable collection-state machine is maintained in the UI.
 */
public final class CollectionRuntimeStateResolver {
    private CollectionRuntimeStateResolver() { }

    public static CollectionRuntimeStatus resolve(String collectionId, List<OperationCenterEntry> entries) {
        if (collectionId == null || collectionId.isBlank() || entries == null || entries.isEmpty()) {
            return CollectionRuntimeStatus.ready();
        }

        List<OperationCenterEntry> relevant = entries.stream()
                .filter(entry -> collectionId.equals(entry.collectionId()))
                .filter(CollectionRuntimeStateResolver::affectsCollectionState)
                .sorted(Comparator.comparing(OperationCenterEntry::updatedAt).reversed())
                .toList();
        if (relevant.isEmpty()) return CollectionRuntimeStatus.ready();

        OperationCenterEntry active = relevant.stream().filter(OperationCenterEntry::active).findFirst().orElse(null);
        if (active != null) return activeStatus(active);

        OperationCenterEntry latest = relevant.getFirst();
        if (latest.stage() == OperationStage.FAILED) {
            String detail = !latest.errorMessage().isBlank() ? latest.errorMessage() : latest.currentItem();
            return new CollectionRuntimeStatus(CollectionRuntimeState.ERROR, -1.0, detail);
        }
        return CollectionRuntimeStatus.ready();
    }

    private static CollectionRuntimeStatus activeStatus(OperationCenterEntry entry) {
        CollectionRuntimeState state;
        if (entry.stage() == OperationStage.CREATING_COLLECTION || entry.kind() == OperationKind.COLLECTION_CREATE) {
            state = CollectionRuntimeState.CREATING;
        } else if (entry.stage() == OperationStage.DELETING_COLLECTION || entry.kind() == OperationKind.COLLECTION_DELETE) {
            state = CollectionRuntimeState.DELETING;
        } else if (entry.stage() == OperationStage.UPDATING_SEARCH_INDEX || entry.kind() == OperationKind.INDEX_REBUILD) {
            state = CollectionRuntimeState.INDEXING;
        } else if (entry.kind() == OperationKind.CATALOG_UPDATE) {
            state = CollectionRuntimeState.UPDATING;
        } else if (entry.kind() == OperationKind.CATALOG_IMPORT) {
            state = CollectionRuntimeState.IMPORTING;
        } else {
            state = switch (entry.stage()) {
                case CHECKING_SERVER, DOWNLOADING, APPLYING_DELETIONS -> CollectionRuntimeState.UPDATING;
                case READING_CATALOG, IMPORTING, UPDATING_AUTHORS -> CollectionRuntimeState.IMPORTING;
                default -> CollectionRuntimeState.READY;
            };
        }
        return new CollectionRuntimeStatus(state, entry.fraction(), entry.currentItem());
    }

    private static boolean affectsCollectionState(OperationCenterEntry entry) {
        return switch (entry.kind()) {
            case COLLECTION_CREATE, COLLECTION_DELETE, CATALOG_IMPORT, CATALOG_UPDATE, INDEX_REBUILD -> true;
            default -> entry.stage() == OperationStage.CREATING_COLLECTION
                    || entry.stage() == OperationStage.DELETING_COLLECTION
                    || entry.stage() == OperationStage.UPDATING_SEARCH_INDEX;
        };
    }
}
