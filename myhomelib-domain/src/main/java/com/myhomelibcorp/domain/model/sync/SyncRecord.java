package com.myhomelibcorp.domain.model.sync;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One versioned user-data mutation. The stable sync id is independent of a local database row id,
 * while {@code localKey} identifies the local entity that the adapter should update.
 *
 * <p>{@code baseVersion} is the version observed before this mutation and is intentionally carried
 * with the record so a later conflict-resolution layer can detect concurrent edits without relying
 * on wall-clock ordering. A tombstone carries no payload and is never silently converted back into
 * a live record.</p>
 */
public record SyncRecord(
        String syncId,
        SyncEntityType entityType,
        String localKey,
        long baseVersion,
        long version,
        Instant updatedAt,
        String deviceId,
        int schemaVersion,
        boolean tombstone,
        Map<String, String> payload
) {
    public SyncRecord {
        syncId = SyncValueValidation.requiredText(syncId, "syncId");
        if (entityType == null) throw new IllegalArgumentException("entityType is required");
        localKey = SyncValueValidation.requiredText(localKey, "localKey");
        if (baseVersion < 0) throw new IllegalArgumentException("baseVersion must be >= 0");
        if (version <= baseVersion) throw new IllegalArgumentException("version must be greater than baseVersion");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
        deviceId = SyncValueValidation.requiredText(deviceId, "deviceId");
        SyncSchema.requireSupported(schemaVersion);
        payload = immutablePayload(payload);
        if (tombstone && !payload.isEmpty()) {
            throw new IllegalArgumentException("tombstone payload must be empty");
        }
    }

    public static SyncRecord live(
            String syncId,
            SyncEntityType entityType,
            String localKey,
            long baseVersion,
            long version,
            Instant updatedAt,
            String deviceId,
            Map<String, String> payload
    ) {
        return new SyncRecord(syncId, entityType, localKey, baseVersion, version, updatedAt, deviceId,
                SyncSchema.CURRENT_VERSION, false, payload);
    }

    public static SyncRecord tombstone(
            String syncId,
            SyncEntityType entityType,
            String localKey,
            long baseVersion,
            long version,
            Instant updatedAt,
            String deviceId
    ) {
        return new SyncRecord(syncId, entityType, localKey, baseVersion, version, updatedAt, deviceId,
                SyncSchema.CURRENT_VERSION, true, Map.of());
    }

    public String logicalKey() {
        return entityType.name() + ":" + syncId;
    }

    private static Map<String, String> immutablePayload(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, String> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            String normalizedKey = SyncValueValidation.requiredText(key, "payload key");
            if (value == null) throw new IllegalArgumentException("payload value is required for " + normalizedKey);
            copy.put(normalizedKey, value);
        });
        return Collections.unmodifiableMap(copy);
    }

}
