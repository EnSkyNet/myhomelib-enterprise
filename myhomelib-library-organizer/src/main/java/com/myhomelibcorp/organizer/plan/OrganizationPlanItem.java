package com.myhomelibcorp.organizer.plan;

import com.myhomelibcorp.organizer.duplicate.DuplicateDecision;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Immutable executable plan item. sourceEntry identifies a logical book inside a source archive. */
public record OrganizationPlanItem(UUID itemId, Path sourcePath, String sourceEntry, String expectedSourceSha256,
                                   Path targetPath, String sourceFormat, String targetFormat,
                                   OrganizationAction action, boolean conversionRequired, String reason,
                                   DuplicateDecision duplicate, int confidence, List<String> warnings,
                                   List<PlanIssue> issues) {
    public OrganizationPlanItem {
        itemId = itemId == null ? UUID.randomUUID() : itemId;
        sourceEntry = safe(sourceEntry);
        expectedSourceSha256 = safe(expectedSourceSha256);
        sourceFormat = safe(sourceFormat);
        targetFormat = safe(targetFormat);
        action = action == null ? OrganizationAction.ERROR : action;
        reason = safe(reason);
        duplicate = duplicate == null ? DuplicateDecision.none() : duplicate;
        confidence = Math.max(0, Math.min(100, confidence));
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        issues = List.copyOf(issues == null ? List.of() : issues);
    }

    /** Compatibility constructor for logical archive sources created from recovery/journal data. */
    public OrganizationPlanItem(UUID itemId, Path sourcePath, String sourceEntry, Path targetPath, String sourceFormat,
                                String targetFormat, OrganizationAction action, boolean conversionRequired,
                                DuplicateDecision duplicate, int confidence, List<String> warnings,
                                List<PlanIssue> issues) {
        this(itemId, sourcePath, sourceEntry, "", targetPath, sourceFormat, targetFormat, action, conversionRequired,
                "", duplicate, confidence, warnings, issues);
    }

    /** Compatibility constructor for ordinary files. */
    public OrganizationPlanItem(UUID itemId, Path sourcePath, Path targetPath, String sourceFormat,
                                String targetFormat, OrganizationAction action, boolean conversionRequired,
                                DuplicateDecision duplicate, int confidence, List<String> warnings,
                                List<PlanIssue> issues) {
        this(itemId, sourcePath, "", "", targetPath, sourceFormat, targetFormat, action, conversionRequired,
                "", duplicate, confidence, warnings, issues);
    }

    public boolean archiveEntry() { return !sourceEntry.isBlank(); }
    public boolean blocking() { return issues.stream().anyMatch(PlanIssue::blocking) || action == OrganizationAction.CONFLICT || action == OrganizationAction.ERROR; }
    private static String safe(String value) { return value == null ? "" : value; }
}
