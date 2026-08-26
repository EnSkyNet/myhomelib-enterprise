package com.myhomelibcorp.application.port.out.collection;

import com.myhomelibcorp.application.collection.CollectionMaintenanceReport;
import com.myhomelibcorp.application.collection.MaintenanceApplyResult;

import java.util.Set;

/** Safe maintenance boundary for the currently active collection. */
public interface CollectionMaintenancePort {
    CollectionMaintenanceReport analyze(String collectionId);
    MaintenanceApplyResult apply(String collectionId, Set<String> issueIds, boolean dryRun);
}
