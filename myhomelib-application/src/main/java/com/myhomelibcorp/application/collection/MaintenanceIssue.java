package com.myhomelibcorp.application.collection;

/** One sampled, stable maintenance issue. target is an id/path understood by the infrastructure adapter. */
public record MaintenanceIssue(
        String issueId,
        MaintenanceIssueType type,
        String target,
        String description,
        boolean repairable,
        boolean destructive
) { }
