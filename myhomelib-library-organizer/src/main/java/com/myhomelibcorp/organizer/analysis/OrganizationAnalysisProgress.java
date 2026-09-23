package com.myhomelibcorp.organizer.analysis;

import java.nio.file.Path;

/** Lightweight progress snapshot for the read-only Organizer analysis phase. */
public record OrganizationAnalysisProgress(long filesDiscovered,
                                           long filesProcessed,
                                           long booksAnalyzed,
                                           long failedFiles,
                                           Path currentPath,
                                           boolean scanning) {
    public OrganizationAnalysisProgress {
        filesDiscovered = Math.max(0, filesDiscovered);
        filesProcessed = Math.max(0, filesProcessed);
        booksAnalyzed = Math.max(0, booksAnalyzed);
        failedFiles = Math.max(0, failedFiles);
    }
}
