package com.myhomelibcorp.application.sync.conflict;

import com.myhomelibcorp.domain.model.sync.SyncRecord;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Bridges domain conflict records to/from UI-safe review DTOs. */
public final class SyncConflictReviewService {
    public List<SyncConflictReviewItem> manualItems(List<SyncConflictResolution> resolutions) {
        if (resolutions == null) return List.of();
        return resolutions.stream()
                .filter(SyncConflictResolution::requiresManualReview)
                .map(r -> new SyncConflictReviewItem(
                        r.local().logicalKey(),
                        r.local().entityType().name(),
                        r.reason(),
                        snapshot(r.local()),
                        snapshot(r.remote())))
                .toList();
    }

    /** Resolves explicit UI selections back to one of the preserved originals. */
    public List<SyncRecord> applySelections(List<SyncConflictResolution> resolutions,
                                            List<SyncConflictReviewSelection> selections) {
        Map<String, SyncConflictResolution> byKey = (resolutions == null ? List.<SyncConflictResolution>of() : resolutions)
                .stream().filter(SyncConflictResolution::requiresManualReview)
                .collect(Collectors.toMap(r -> r.local().logicalKey(), Function.identity()));
        if (selections == null) selections = List.of();
        if (selections.size() != byKey.size()) {
            throw new IllegalArgumentException("Every manual conflict requires an explicit selection");
        }
        return selections.stream().map(selection -> {
            SyncConflictResolution conflict = byKey.get(selection.logicalKey());
            if (conflict == null) throw new IllegalArgumentException("Unknown manual conflict: " + selection.logicalKey());
            return selection.side() == SyncConflictReviewSelection.Side.LOCAL ? conflict.local() : conflict.remote();
        }).toList();
    }

    private static String snapshot(SyncRecord record) {
        return "device=" + record.deviceId()
                + "; version=" + record.version()
                + "; updatedAt=" + record.updatedAt()
                + "; tombstone=" + record.tombstone()
                + "; payload=" + record.payload();
    }
}
