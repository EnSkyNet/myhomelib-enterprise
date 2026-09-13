package com.myhomelibcorp.application.health;

/** One actionable drill-down row in the library health dashboard. */
public record LibraryHealthIssue(
        LibraryHealthIssueType type,
        LibraryHealthSeverity severity,
        long count,
        String title,
        String detail,
        String action
) {
    public LibraryHealthIssue {
        if (type == null) throw new IllegalArgumentException("type is required");
        severity = severity == null ? LibraryHealthSeverity.INFO : severity;
        count = Math.max(0L, count);
        title = safe(title);
        detail = safe(detail);
        action = safe(action);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
