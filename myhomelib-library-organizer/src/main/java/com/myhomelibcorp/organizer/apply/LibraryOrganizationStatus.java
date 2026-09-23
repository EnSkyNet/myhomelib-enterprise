package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.journal.OrganizationJournalItem;
import com.myhomelibcorp.organizer.journal.OrganizationJournalOperation;

import java.util.List;

public record LibraryOrganizationStatus(OrganizationJournalOperation operation,
                                        List<OrganizationJournalItem> items,
                                        boolean canRollback,
                                        boolean needsManualReview,
                                        RecoveryDisposition disposition,
                                        int committedItems,
                                        int recoverablePendingItems,
                                        int ambiguousItems,
                                        boolean crashManifestPresent) {
    public LibraryOrganizationStatus {
        items=List.copyOf(items==null?List.of():items);
        disposition = disposition == null ? RecoveryDisposition.MANUAL_REVIEW : disposition;
        committedItems = Math.max(0, committedItems);
        recoverablePendingItems = Math.max(0, recoverablePendingItems);
        ambiguousItems = Math.max(0, ambiguousItems);
    }
}
