package com.myhomelibcorp.organizer.journal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationJournalPort {
    void begin(OrganizationJournalOperation operation, List<OrganizationJournalItem> items);
    void updateOperation(UUID operationId, OrganizationOperationState state, String summaryJson);
    void updateItem(OrganizationJournalItem item);
    /**
     * Atomically change an existing item only if its complete durable record still
     * equals {@code expected}. Used by Undo after touching the filesystem: a late
     * concurrent journal writer must never be silently overwritten.
     *
     * Custom adapters fail closed until they provide a real atomic implementation.
     * A read followed by {@link #updateItem} does NOT satisfy this contract.
     *
     * @return true if exactly one existing item was changed, false if it differed
     *         or disappeared; never inserts an item.
     */
    default boolean updateItemIfUnchanged(OrganizationJournalItem expected,
                                          OrganizationJournalItem replacement) {
        throw new UnsupportedOperationException("Atomic organizer journal compare-and-swap is required for Undo");
    }

    /**
     * Atomically finalize Undo only if the operation's durable identity and
     * state remain unchanged and the EXACT original set of item IDs now consists
     * exclusively of ROLLED_BACK/SKIPPED records. This check and update MUST be
     * performed by the persistent adapter as one database statement/transaction.
     *
     * Fail closed for custom/legacy adapters. A read followed by updateOperation
     * would reintroduce the V30 race.
     */
    default boolean finalizeRollbackIfUnchanged(OrganizationJournalOperation expectedOperation,
                                                List<UUID> expectedItemIds, String summaryJson) {
        throw new UnsupportedOperationException("Atomic organizer rollback finalization is required");
    }

    /**
     * Record independent, non-mutating item states in batches. The default preserves compatibility
     * with custom adapters; durable adapters should commit the full batch transactionally.
     */
    default void updateItems(List<OrganizationJournalItem> items) {
        for (OrganizationJournalItem item : items) updateItem(item);
    }
    /**
     * Authoritative durable record for this exact operation, including terminal states.
     * Do not infer collection identity from a caller-supplied id or the incomplete list.
     * Custom journal implementations must implement this to permit Undo.
     */
    default Optional<OrganizationJournalOperation> findOperation(UUID operationId) {
        return Optional.empty(); // Fail closed for legacy/custom adapters.
    }
    List<OrganizationJournalOperation> incompleteOperations();
    List<OrganizationJournalItem> items(UUID operationId);

    /**
     * Read the CURRENT durable row before a destructive rollback action.
     * The default keeps custom adapters source-compatible; large persistent
     * implementations should override it with a keyed query.
     */
    default Optional<OrganizationJournalItem> findItem(UUID operationId, UUID itemId) {
        List<OrganizationJournalItem> matches = items(operationId).stream()
                .filter(item -> itemId.equals(item.itemId())).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }
}
