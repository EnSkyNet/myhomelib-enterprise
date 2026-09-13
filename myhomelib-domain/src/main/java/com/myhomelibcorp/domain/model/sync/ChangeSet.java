package com.myhomelibcorp.domain.model.sync;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable ordered bundle of user-data changes emitted by one device. */
public record ChangeSet(
        String changeSetId,
        String sourceDeviceId,
        long sequence,
        String previousCursor,
        Instant createdAt,
        int schemaVersion,
        List<SyncRecord> records
) {
    public ChangeSet {
        changeSetId = SyncValueValidation.requiredText(changeSetId, "changeSetId");
        sourceDeviceId = SyncValueValidation.requiredText(sourceDeviceId, "sourceDeviceId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be >= 1");
        previousCursor = previousCursor == null ? "" : previousCursor.trim();
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        SyncSchema.requireSupported(schemaVersion);
        records = records == null ? List.of() : Collections.unmodifiableList(List.copyOf(records));

        Set<String> seen = new HashSet<>();
        for (SyncRecord record : records) {
            if (record == null) throw new IllegalArgumentException("records must not contain null");
            if (record.schemaVersion() != schemaVersion) {
                throw new IllegalArgumentException("record schema version differs from change set schema");
            }
            if (!record.deviceId().equals(sourceDeviceId)) {
                throw new IllegalArgumentException("record deviceId differs from sourceDeviceId");
            }
            if (!seen.add(record.logicalKey())) {
                throw new IllegalArgumentException("duplicate entity in one change set: " + record.logicalKey());
            }
        }
    }

    public String cursor() {
        return sourceDeviceId + ":" + sequence;
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

}
