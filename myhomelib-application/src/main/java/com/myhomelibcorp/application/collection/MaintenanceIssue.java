package com.myhomelibcorp.application.collection;

import java.util.List;

/**
 * One sampled, stable maintenance issue. User-facing text is represented by a
 * localization key plus arguments; raw storage/database details are kept
 * separately for diagnostics and must not be rendered directly by the UI.
 */
public record MaintenanceIssue(
        String issueId,
        MaintenanceIssueType type,
        String target,
        String messageKey,
        List<String> messageArguments,
        String technicalDetail,
        boolean repairable,
        boolean destructive
) {
    public MaintenanceIssue {
        issueId = issueId == null ? "" : issueId;
        target = target == null ? "" : target;
        messageKey = messageKey == null ? "" : messageKey;
        messageArguments = messageArguments == null ? List.of() : List.copyOf(messageArguments);
        technicalDetail = technicalDetail == null ? "" : technicalDetail;
    }
}
