package com.myhomelibcorp.application.collection;

import java.nio.file.Path;

public record MaintenanceApplyResult(
        boolean dryRun,
        Path backupFile,
        long requested,
        long applied,
        long skipped,
        CollectionMaintenanceReport before,
        CollectionMaintenanceReport after
) { }
