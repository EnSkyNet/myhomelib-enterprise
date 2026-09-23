package com.myhomelibcorp.organizer.journal;

import java.util.List;
import java.util.UUID;

public interface OrganizationJournalPort {
    void begin(OrganizationJournalOperation operation, List<OrganizationJournalItem> items);
    void updateOperation(UUID operationId, OrganizationOperationState state, String summaryJson);
    void updateItem(OrganizationJournalItem item);
    List<OrganizationJournalOperation> incompleteOperations();
    List<OrganizationJournalItem> items(UUID operationId);
}
