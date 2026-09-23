package com.myhomelibcorp.organizer.plan;

import com.myhomelibcorp.organizer.policy.OrganizerProfile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrganizationPlan(UUID operationId, Instant createdAt, OrganizerProfile policy,
                               Path sourceRoot, Path destinationRoot,
                               List<OrganizationPlanItem> actions, PlanStatistics statistics,
                               List<PlanIssue> issues, String expectedCollectionId) {
    public OrganizationPlan {
        operationId=operationId==null?UUID.randomUUID():operationId;createdAt=createdAt==null?Instant.now():createdAt;
        actions=List.copyOf(actions==null?List.of():actions);issues=List.copyOf(issues==null?List.of():issues);
        expectedCollectionId = expectedCollectionId == null ? "" : expectedCollectionId;
    }

    /** Backward-compatible plan constructor for collection-independent organizer callers. */
    public OrganizationPlan(UUID operationId, Instant createdAt, OrganizerProfile policy,
                            Path sourceRoot, Path destinationRoot, List<OrganizationPlanItem> actions,
                            PlanStatistics statistics, List<PlanIssue> issues) {
        this(operationId, createdAt, policy, sourceRoot, destinationRoot, actions, statistics, issues, "");
    }
    public boolean executable(){return actions.stream().noneMatch(OrganizationPlanItem::blocking)&&issues.stream().noneMatch(PlanIssue::blocking);}

    public OrganizationPlan withActions(List<OrganizationPlanItem> selectedActions) {
        List<OrganizationPlanItem> selected = List.copyOf(selectedActions == null ? List.of() : selectedActions);
        return new OrganizationPlan(operationId, createdAt, policy, sourceRoot, destinationRoot,
                selected, PlanStatistics.from(selected), issues, expectedCollectionId);
    }

    public OrganizationPlan withExpectedCollectionId(String collectionId) {
        return new OrganizationPlan(operationId, createdAt, policy, sourceRoot, destinationRoot,
                actions, statistics, issues, collectionId);
    }
}
