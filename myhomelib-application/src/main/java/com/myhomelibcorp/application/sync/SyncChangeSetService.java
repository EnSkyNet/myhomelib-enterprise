package com.myhomelibcorp.application.sync;

import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import com.myhomelibcorp.domain.model.sync.SyncSchema;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Creates deterministic, bounded change sets from a batch of local mutations. */
public final class SyncChangeSetService {
    private final Clock clock;
    private final int maxRecords;

    public SyncChangeSetService(Clock clock, int maxRecords) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxRecords < 1 || maxRecords > 10_000) {
            throw new IllegalArgumentException("maxRecords must be between 1 and 10000");
        }
        this.maxRecords = maxRecords;
    }

    public ChangeSet create(String deviceId, long sequence, String previousCursor, List<SyncRecord> mutations) {
        String normalizedDevice = required(deviceId, "deviceId");
        if (mutations == null) mutations = List.of();

        Map<String, SyncRecord> latest = new LinkedHashMap<>();
        for (SyncRecord record : mutations) {
            if (record == null) throw new IllegalArgumentException("mutations must not contain null");
            if (!record.deviceId().equals(normalizedDevice)) {
                throw new IllegalArgumentException("mutation device differs from change set device");
            }
            SyncRecord previous = latest.get(record.logicalKey());
            if (previous == null || record.version() > previous.version()) {
                latest.put(record.logicalKey(), record);
            } else if (record.version() == previous.version() && !record.equals(previous)) {
                throw new IllegalArgumentException("same entity/version has divergent payload: " + record.logicalKey());
            }
        }

        List<SyncRecord> records = new ArrayList<>(latest.values());
        records.sort(Comparator.comparing(SyncRecord::logicalKey));
        if (records.size() > maxRecords) {
            throw new IllegalArgumentException("change set exceeds maxRecords=" + maxRecords);
        }
        return new ChangeSet(UUID.randomUUID().toString(), normalizedDevice, sequence, previousCursor,
                clock.instant(), SyncSchema.CURRENT_VERSION, records);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
