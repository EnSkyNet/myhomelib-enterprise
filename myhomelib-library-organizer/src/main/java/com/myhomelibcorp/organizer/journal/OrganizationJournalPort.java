package com.myhomelibcorp.organizer.journal;

import java.util.List;
import java.util.UUID;

public interface OrganizationJournalPort {
    void begin(OrganizationJournalOperation operation, List<OrganizationJournalItem> items);
    void updateOperation(UUID operationId, OrganizationOperationState state, String summaryJson);
    void updateItem(OrganizationJournalItem item);
    /**
     * Record independent, non-mutating item states in batches. The default preserves compatibility
     * with custom adapters; durable adapters should commit the full batch transactionally.
     */
    default void updateItems(List<OrganizationJournalItem> items) {
        for (OrganizationJournalItem item : items) updateItem(item);
    }
    List<OrganizationJournalOperation> incompleteOperations();
    List<OrganizationJournalItem> items(UUID operationId);
}
