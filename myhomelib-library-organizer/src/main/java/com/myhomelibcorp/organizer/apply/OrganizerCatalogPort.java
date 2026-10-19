package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.plan.OrganizationPlanItem;

/** Optional managed-collection hook. Filesystem finalization always happens before catalog mutation. */
public interface OrganizerCatalogPort {
    boolean supports(OrganizationPlanItem item);

    /**
     * Reject unsafe catalog transitions before any source file is copied or removed.
     * Catalog adapters must not silently ignore a managed file whose destination
     * cannot be represented by their storage projection.
     */
    default void validateBeforeFilesystemCommit(OrganizationPlanItem item) throws Exception { }

    void afterFilesystemCommit(OrganizationPlanItem item) throws Exception;
    void rollbackCatalog(OrganizationPlanItem item) throws Exception;
}
