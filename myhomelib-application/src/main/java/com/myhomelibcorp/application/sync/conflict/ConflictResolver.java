package com.myhomelibcorp.application.sync.conflict;

import com.myhomelibcorp.domain.model.sync.SyncRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic conflict resolver for versioned user-data sync records.
 *
 * <p>The resolver never silently turns a live record into a tombstone. When two edits cannot be
 * merged without losing information, the result is {@link ConflictResolutionDecision#MANUAL} and
 * both originals remain available to the review UI.</p>
 */
public final class ConflictResolver {
    private static final String TAG_SEPARATOR = "\u001F";

    public SyncConflictResolution resolve(SyncRecord local, SyncRecord remote, String resolutionDeviceId) {
        return resolve(null, local, remote, resolutionDeviceId);
    }

    /**
     * Three-way resolution. {@code ancestor} is optional but, when present, allows annotations with
     * independent field edits to be merged safely instead of being escalated to manual review.
     */
    public SyncConflictResolution resolve(SyncRecord ancestor, SyncRecord local, SyncRecord remote,
                                          String resolutionDeviceId) {
        validatePair(ancestor, local, remote);
        String deviceId = required(resolutionDeviceId, "resolutionDeviceId");

        if (local.equals(remote)) {
            return automatic(local, remote, ConflictResolutionDecision.NO_CONFLICT, local,
                    "Both devices contain the same mutation");
        }
        if (local.tombstone() != remote.tombstone()) {
            return manual(local, remote, "Delete conflicts with a live edit; user review is required");
        }
        if (local.tombstone()) {
            SyncRecord newest = newest(local, remote);
            if (newest == null) return manual(local, remote, "Concurrent tombstones have no deterministic order");
            return automatic(local, remote, ConflictResolutionDecision.LATEST, newest,
                    "Both records are tombstones; the newest tombstone wins");
        }

        return switch (local.entityType()) {
            case READING_PROGRESS -> resolveProgress(local, remote, deviceId);
            case ANNOTATION -> resolveAnnotation(ancestor, local, remote, deviceId);
            case BOOKMARK, RATING, FAVORITE, SETTING -> resolveLatest(local, remote, deviceId);
            case GROUP -> manual(local, remote, "Concurrent group edits require explicit review");
        };
    }

    private static SyncConflictResolution resolveProgress(SyncRecord local, SyncRecord remote, String deviceId) {
        double lp = number(local.payload().get("percent"), "local progress percent");
        double rp = number(remote.payload().get("percent"), "remote progress percent");
        if (Double.compare(lp, rp) != 0) {
            SyncRecord chosen = lp > rp ? local : remote;
            return automatic(local, remote, ConflictResolutionDecision.FURTHEST_PROGRESS,
                    resolvedFrom(chosen, local, remote, deviceId, chosen.payload()),
                    "Reading progress keeps the furthest position");
        }
        SyncRecord newest = newest(local, remote);
        if (newest == null) return manual(local, remote, "Equal progress has concurrent divergent details");
        return automatic(local, remote, ConflictResolutionDecision.LATEST,
                resolvedFrom(newest, local, remote, deviceId, newest.payload()),
                "Equal progress keeps the newest deterministic mutation");
    }

    private static SyncConflictResolution resolveLatest(SyncRecord local, SyncRecord remote, String deviceId) {
        SyncRecord newest = newest(local, remote);
        if (newest == null) {
            return manual(local, remote, "Concurrent edits have the same timestamp and divergent values");
        }
        return automatic(local, remote, ConflictResolutionDecision.LATEST,
                resolvedFrom(newest, local, remote, deviceId, newest.payload()),
                "Latest timestamp wins for this scalar entity type");
    }

    private static SyncConflictResolution resolveAnnotation(SyncRecord ancestor, SyncRecord local,
                                                             SyncRecord remote, String deviceId) {
        Map<String, String> merged = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(local.payload().keySet());
        keys.addAll(remote.payload().keySet());
        if (ancestor != null) keys.addAll(ancestor.payload().keySet());

        List<String> unresolved = new ArrayList<>();
        for (String key : keys) {
            String base = ancestor == null ? null : ancestor.payload().get(key);
            String lv = local.payload().get(key);
            String rv = remote.payload().get(key);
            if (Objects.equals(lv, rv)) {
                putNullable(merged, key, lv);
                continue;
            }
            if ("tags".equals(key)) {
                merged.put(key, mergeTags(lv, rv));
                continue;
            }
            if (ancestor != null) {
                boolean localChanged = !Objects.equals(base, lv);
                boolean remoteChanged = !Objects.equals(base, rv);
                if (localChanged && !remoteChanged) {
                    putNullable(merged, key, lv);
                    continue;
                }
                if (!localChanged && remoteChanged) {
                    putNullable(merged, key, rv);
                    continue;
                }
            }
            unresolved.add(key);
        }
        if (!unresolved.isEmpty()) {
            return manual(local, remote, "Annotation fields need review: " + String.join(", ", unresolved));
        }
        return automatic(local, remote, ConflictResolutionDecision.MERGED,
                resolvedFrom(local, local, remote, deviceId, merged),
                "Independent annotation edits were merged without discarding either side");
    }

    private static SyncRecord resolvedFrom(SyncRecord template, SyncRecord local, SyncRecord remote,
                                           String deviceId, Map<String, String> payload) {
        long baseVersion = Math.max(local.version(), remote.version());
        long version = Math.addExact(baseVersion, 1);
        Instant updatedAt = local.updatedAt().isAfter(remote.updatedAt()) ? local.updatedAt() : remote.updatedAt();
        return SyncRecord.live(template.syncId(), template.entityType(), template.localKey(), baseVersion, version,
                updatedAt, deviceId, payload);
    }

    private static SyncRecord newest(SyncRecord local, SyncRecord remote) {
        int time = local.updatedAt().compareTo(remote.updatedAt());
        if (time > 0) return local;
        if (time < 0) return remote;
        if (local.version() > remote.version()) return local;
        if (remote.version() > local.version()) return remote;
        return null;
    }

    private static SyncConflictResolution automatic(SyncRecord local, SyncRecord remote,
                                                     ConflictResolutionDecision decision, SyncRecord resolved,
                                                     String reason) {
        return new SyncConflictResolution(local, remote, decision, resolved, reason);
    }

    private static SyncConflictResolution manual(SyncRecord local, SyncRecord remote, String reason) {
        return new SyncConflictResolution(local, remote, ConflictResolutionDecision.MANUAL, null, reason);
    }

    private static void validatePair(SyncRecord ancestor, SyncRecord local, SyncRecord remote) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(remote, "remote");
        if (!local.logicalKey().equals(remote.logicalKey())) {
            throw new IllegalArgumentException("Conflict records must address the same logical entity");
        }
        if (local.schemaVersion() != remote.schemaVersion()) {
            throw new IllegalArgumentException("Conflict records use different schema versions");
        }
        if (ancestor != null) {
            if (!ancestor.logicalKey().equals(local.logicalKey())) {
                throw new IllegalArgumentException("Ancestor must address the same logical entity");
            }
            if (ancestor.schemaVersion() != local.schemaVersion()) {
                throw new IllegalArgumentException("Ancestor schema differs from conflict records");
            }
        }
    }

    private static String mergeTags(String left, String right) {
        Set<String> tags = new TreeSet<>();
        addTags(tags, left);
        addTags(tags, right);
        return String.join(TAG_SEPARATOR, tags);
    }

    private static void addTags(Set<String> target, String encoded) {
        if (encoded == null || encoded.isBlank()) return;
        for (String tag : encoded.split(TAG_SEPARATOR, -1)) if (!tag.isBlank()) target.add(tag);
    }

    private static void putNullable(Map<String, String> target, String key, String value) {
        if (value != null) target.put(key, value);
    }

    private static double number(String value, String field) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) throw new NumberFormatException("non-finite");
            return parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field + " must be a finite number", e);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
