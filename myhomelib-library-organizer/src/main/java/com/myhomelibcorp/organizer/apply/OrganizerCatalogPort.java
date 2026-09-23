package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.plan.OrganizationPlanItem;

/** Optional managed-collection hook. Filesystem finalization always happens before catalog mutation. */
public interface OrganizerCatalogPort {
    boolean supports(OrganizationPlanItem item);
    void afterFilesystemCommit(OrganizationPlanItem item) throws Exception;
    void rollbackCatalog(OrganizationPlanItem item) throws Exception;
}
