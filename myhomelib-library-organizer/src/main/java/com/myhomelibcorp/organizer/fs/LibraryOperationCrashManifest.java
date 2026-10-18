package com.myhomelibcorp.organizer.fs;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;
import com.myhomelibcorp.organizer.plan.OrganizationPlan;
import com.myhomelibcorp.organizer.plan.OrganizationPlanItem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Filesystem-side recovery journal for Organizer operations.
 *
 * <p>The database journal remains authoritative for managed state, while this manifest intentionally
 * lives inside the operation workspace so external-folder operations can still be inspected after a
 * process crash. The initial snapshot is published once; subsequent durable state events are
 * appended to a checksum-framed event log, without rewriting the initial snapshot.</p>
 */
public final class LibraryOperationCrashManifest {
    private final UUID operationId;
    private final String profileId;
    private final Path sourceRoot;
    private final Path destinationRoot;
    private final Path manifestPath;
    private final Map<UUID, ItemSnapshot> items = new LinkedHashMap<>();
    private OrganizationOperationState operationState = OrganizationOperationState.PLANNED;
    private String operationMessage = "";

    private LibraryOperationCrashManifest(OrganizationPlan plan, Path manifestPath) {
        this.operationId = Objects.requireNonNull(plan.operationId(), "operationId");
        this.profileId = plan.policy() == null ? "" : safe(plan.policy().id());
        this.sourceRoot = plan.sourceRoot();
        this.destinationRoot = plan.destinationRoot();
        this.manifestPath = Objects.requireNonNull(manifestPath, "manifestPath");
        for (OrganizationPlanItem item : plan.actions()) {
            Objects.requireNonNull(item, "plan item");
            ItemSnapshot snapshot = new ItemSnapshot(item.itemId(), item.action().name(), item.sourcePath(), item.sourceEntry(),
                    item.targetPath(), OrganizationItemState.PLANNED, item.expectedSourceSha256(), "", null, "");
            if (items.putIfAbsent(snapshot.itemId(), snapshot) != null) {
                throw new IllegalArgumentException("Duplicate item id in organizer crash-manifest plan");
            }
        }
    }

    public static LibraryOperationCrashManifest create(OrganizationPlan plan, Path manifestPath) throws Exception {
        LibraryOperationCrashManifest manifest = new LibraryOperationCrashManifest(plan, manifestPath);
        // A crash manifest is immutable once created. Never replace an existing snapshot
        // and then fail to create its already-existing event log: that would destroy the
        // recovery evidence from the original operation.
        Path safeManifest = OrganizerPathSafety.canonicalForCreation(manifestPath, "Organizer crash manifest");
        Path safeEvents = OrganizerPathSafety.canonicalForCreation(
                ManifestEventLog.path(safeManifest), "Organizer crash-manifest events");
        if (Files.exists(safeManifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(safeManifest.toString());
        }
        if (Files.exists(safeEvents, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(safeEvents.toString());
        }
        manifest.persist();
        ManifestEventLog.create(manifestPath);
        return manifest;
    }

    public synchronized void operationState(OrganizationOperationState state, String message) throws Exception {
        Objects.requireNonNull(state, "state");
        String nextMessage = safe(message);
        ManifestEventLog.append(manifestPath, "{\"type\":\"operation\",\"state\":\""
                + state.name() + "\",\"message\":\"" + json(nextMessage)
                + "\",\"updatedAt\":\"" + Instant.now() + "\"}");
        // Only expose a state that has made it into the durable event log.
        this.operationState = state;
        this.operationMessage = nextMessage;
    }

    public synchronized void itemState(OrganizationPlanItem item, OrganizationItemState state,
                                       String sourceSha256, String targetSha256, Path backupPath,
                                       String message) throws Exception {
        Objects.requireNonNull(item, "item");
        requireOriginalItem(item);
        ItemSnapshot snapshot = new ItemSnapshot(item.itemId(), item.action().name(), item.sourcePath(), item.sourceEntry(),
                item.targetPath(), Objects.requireNonNull(state, "state"), safe(sourceSha256),
                safe(targetSha256), backupPath, safe(message));
        ManifestEventLog.append(manifestPath, itemEventJson(snapshot));
        items.put(item.itemId(), snapshot);
    }

    /** Persist a batch of no-file-change decisions with one durable event-log sync. */
    public synchronized void skippedItems(java.util.List<OrganizationPlanItem> skipped) throws Exception {
        if (skipped == null || skipped.isEmpty()) return;
        java.util.List<ItemSnapshot> snapshots = new java.util.ArrayList<>(skipped.size());
        java.util.List<String> events = new java.util.ArrayList<>(skipped.size());
        for (OrganizationPlanItem item : skipped) {
            if (item == null || !switch (item.action()) {
                case NOOP, KEEP_AS_IS, REVIEW_DUPLICATE, SKIP_UNSUPPORTED -> true;
                default -> false;
            }) throw new IllegalArgumentException("Only non-mutating organizer items may be batched");
            requireOriginalItem(item);
            ItemSnapshot snapshot = new ItemSnapshot(item.itemId(), item.action().name(), item.sourcePath(),
                    item.sourceEntry(), item.targetPath(), OrganizationItemState.SKIPPED, "", "", null, "");
            snapshots.add(snapshot);
            events.add(itemEventJson(snapshot));
        }
        ManifestEventLog.appendBatch(manifestPath, events);
        for (ItemSnapshot snapshot : snapshots) items.put(snapshot.itemId(), snapshot);
    }

    public Path path() { return manifestPath; }

    private void requireOriginalItem(OrganizationPlanItem item) {
        ItemSnapshot previous = items.get(item.itemId());
        if (previous == null) {
            throw new IllegalArgumentException("Unknown item in organizer crash manifest");
        }
        if (!previous.action().equals(item.action().name())
                || !Objects.equals(previous.sourcePath(), item.sourcePath())
                || !Objects.equals(previous.sourceEntry(), safe(item.sourceEntry()))
                || !Objects.equals(previous.targetPath(), item.targetPath())) {
            throw new IllegalArgumentException("Organizer crash-manifest item identity changed");
        }
    }

    private void persist() throws Exception {
        Path safeManifest = OrganizerPathSafety.canonicalForCreation(manifestPath, "Organizer crash-manifest path");
        Path parent = safeManifest.getParent();
        if (parent == null) throw new IllegalArgumentException("Manifest path has no parent");
        OrganizerPathSafety.createDirectoriesNoLinks(parent, "Organizer crash-manifest directory");
        Path temp = Files.createTempFile(parent, ".manifest-", ".tmp");
        String tempHash = null;
        try {
            OrganizerPathSafety.canonicalExistingFile(temp, "Organizer crash-manifest temporary file");
            Files.writeString(temp, json(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            tempHash = SafeFilePublication.sha256(temp, "Organizer crash-manifest temporary file");
            SafeFilePublication.publishReplacingIfUnchanged(
                    temp, safeManifest, null, "Organizer crash-manifest");
            OrganizerPathSafety.canonicalExistingFile(safeManifest, "Organizer persisted crash-manifest");
        } finally {
            if (Files.exists(temp, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (tempHash != null) {
                        SafeFilePublication.removeIfOwned(temp, tempHash, "Organizer crash-manifest temp cleanup");
                    }
                } catch (Exception ignored) {
                    // Preserve unverified bytes as evidence rather than deleting a racing replacement.
                }
            }
        }
    }

    private String itemEventJson(ItemSnapshot item) {
        return "{\"type\":\"item\","
                + "\"itemId\":\"" + item.itemId() + "\","
                + "\"state\":\"" + item.state().name() + "\","
                + "\"sourceSha256\":\"" + json(item.sourceSha256()) + "\","
                + "\"targetSha256\":\"" + json(item.targetSha256()) + "\","
                + "\"backupPath\":" + pathJson(item.backupPath()) + ","
                + "\"message\":\"" + json(item.message()) + "\"}";
    }

    private String json() {
        StringBuilder out = new StringBuilder(2048);
        out.append("{\n")
                .append("  \"schemaVersion\": 3,\n")
                .append("  \"operationId\": \"").append(json(operationId.toString())).append("\",\n")
                .append("  \"profileId\": \"").append(json(profileId)).append("\",\n")
                .append("  \"sourceRoot\": ").append(pathJson(sourceRoot)).append(",\n")
                .append("  \"destinationRoot\": ").append(pathJson(destinationRoot)).append(",\n")
                .append("  \"state\": \"").append(operationState.name()).append("\",\n")
                .append("  \"message\": \"").append(json(operationMessage)).append("\",\n")
                .append("  \"updatedAt\": \"").append(Instant.now()).append("\",\n")
                .append("  \"items\": [\n");
        int index = 0;
        for (ItemSnapshot item : items.values()) {
            if (index++ > 0) out.append(",\n");
            out.append("    {")
                    .append("\"itemId\":\"").append(json(item.itemId().toString())).append("\",")
                    .append("\"action\":\"").append(json(item.action())).append("\",")
                    .append("\"sourcePath\":").append(pathJson(item.sourcePath())).append(',')
                    .append("\"sourceEntry\":\"").append(json(item.sourceEntry())).append("\",")
                    .append("\"targetPath\":").append(pathJson(item.targetPath())).append(',')
                    .append("\"state\":\"").append(item.state().name()).append("\",")
                    .append("\"sourceSha256\":\"").append(json(item.sourceSha256())).append("\",")
                    .append("\"targetSha256\":\"").append(json(item.targetSha256())).append("\",")
                    .append("\"backupPath\":").append(pathJson(item.backupPath())).append(',')
                    .append("\"message\":\"").append(json(item.message())).append("\"}");
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private static String pathJson(Path path) {
        return path == null ? "null" : "\"" + json(path.toAbsolutePath().normalize().toString()) + "\"";
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String json(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private record ItemSnapshot(UUID itemId, String action, Path sourcePath, String sourceEntry, Path targetPath,
                                OrganizationItemState state, String sourceSha256, String targetSha256,
                                Path backupPath, String message) {
        private ItemSnapshot { sourceEntry = sourceEntry == null ? "" : sourceEntry; }
    }
}
