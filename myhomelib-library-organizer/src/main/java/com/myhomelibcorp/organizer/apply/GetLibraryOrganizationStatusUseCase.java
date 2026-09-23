package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.fs.LibraryOperationCrashManifestReader;
import com.myhomelibcorp.organizer.fs.LibraryOperationCrashSnapshot;
import com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace;
import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import com.myhomelibcorp.organizer.journal.*;
import com.myhomelibcorp.organizer.plan.OrganizationAction;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Enumeration;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import java.util.Set;

@Service
public final class GetLibraryOrganizationStatusUseCase {
    private final OrganizationJournalPort journal;
    private final LibraryOperationCrashManifestReader manifestReader;

    public GetLibraryOrganizationStatusUseCase(OrganizationJournalPort journal, LibraryOperationCrashManifestReader manifestReader){
        this.journal=Objects.requireNonNull(journal,"journal");
        this.manifestReader=Objects.requireNonNull(manifestReader,"manifestReader");
    }

    public List<LibraryOrganizationStatus> incomplete(){
        List<LibraryOrganizationStatus> result = new ArrayList<>();
        List<OrganizationJournalOperation> database = journal.incompleteOperations();
        Set<UUID> knownIds = new HashSet<>();
        for (OrganizationJournalOperation operation : database) {
            knownIds.add(operation.operationId());
            result.add(classify(operation, journal.items(operation.operationId())));
        }
        result.addAll(orphanManifestStatuses(knownIds));
        result.sort(Comparator.comparing((LibraryOrganizationStatus status) -> status.operation().startedAt(),
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return List.copyOf(result);
    }

    /**
     * A crash manifest without a matching DB journal is surfaced for manual review only.
     * We intentionally do not import it into the authoritative journal or enable automatic Undo,
     * because a standalone JSON file is recoverability evidence, not a trusted transaction record.
     */
    private List<LibraryOrganizationStatus> orphanManifestStatuses(Set<UUID> knownIds) {
        Path base = LibraryOperationWorkspace.baseRoot();
        List<LibraryOrganizationStatus> result = new ArrayList<>();
        try {
            if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return List.of();
            base = OrganizerPathSafety.canonicalExistingDirectory(base, "Organizer recovery cache");
        } catch (Exception unsafeBase) {
            return List.of();
        }
        try (var directories = Files.list(base)) {
            for (Path directory : directories.filter(path -> {
                try {
                    OrganizerPathSafety.canonicalExistingDirectory(path, "Organizer recovery workspace");
                    return true;
                } catch (Exception unsafe) {
                    return false;
                }
            }).toList()) {
                UUID directoryId;
                try { directoryId = UUID.fromString(directory.getFileName().toString()); }
                catch (RuntimeException ignored) { continue; }
                if (knownIds.contains(directoryId)) continue;
                Path manifest = directory.resolve("manifest.json");
                if (!regular(manifest)) continue;
                try {
                    LibraryOperationCrashSnapshot snapshot = manifestReader.read(manifest);
                    if (!directoryId.equals(snapshot.operationId())) {
                        result.add(untrustedManifestStatus(directoryId, directory, "Manifest operation id does not match workspace directory"));
                        continue;
                    }
                    if (snapshot.state() == OrganizationOperationState.COMPLETED || snapshot.state() == OrganizationOperationState.ROLLED_BACK) {
                        // A terminal orphan workspace is cleanup residue, not an incomplete operation.
                        continue;
                    }
                    List<OrganizationJournalItem> items = snapshot.items().stream().map(item -> new OrganizationJournalItem(
                            snapshot.operationId(), item.itemId(), item.sourcePath(), item.sourceEntry(), item.targetPath(),
                            item.sourceSha256(), item.targetSha256(), item.action(), item.state(), item.backupPath(),
                            "", 0, 0, 0, 0.0, "{}", "ORPHAN_MANIFEST", item.message())).toList();
                    OrganizationJournalOperation operation = new OrganizationJournalOperation(snapshot.operationId(), snapshot.profileId(),
                            snapshot.sourceRoot(), snapshot.destinationRoot(), snapshot.state(), snapshot.updatedAt(), null,
                            "Crash manifest exists without an authoritative database journal");
                    result.add(new LibraryOrganizationStatus(operation, items, false, true, RecoveryDisposition.MANUAL_REVIEW,
                            (int) items.stream().filter(item -> item.state() == OrganizationItemState.COMMITTED).count(),
                            0, Math.max(1, items.size()), true));
                } catch (Exception invalidManifest) {
                    result.add(untrustedManifestStatus(directoryId, directory, "Crash manifest cannot be parsed: " + invalidManifest.getClass().getSimpleName()));
                }
            }
        } catch (Exception ignored) {
            // The authoritative database statuses remain usable even if the cache directory cannot be listed.
        }
        return result;
    }

    private LibraryOrganizationStatus untrustedManifestStatus(UUID operationId, Path workspace, String message) {
        Instant timestamp;
        try { timestamp = Files.getLastModifiedTime(workspace.resolve("manifest.json")).toInstant(); }
        catch (Exception ignored) { timestamp = Instant.EPOCH; }
        OrganizationJournalOperation operation = new OrganizationJournalOperation(operationId, "recovery-manifest", workspace, workspace,
                OrganizationOperationState.FAILED, timestamp, null, message);
        return new LibraryOrganizationStatus(operation, List.of(), false, true, RecoveryDisposition.MANUAL_REVIEW,
                0, 0, 1, true);
    }

    private LibraryOrganizationStatus classify(OrganizationJournalOperation operation, List<OrganizationJournalItem> items) {
        int committed = 0;
        int recoverablePending = 0;
        int ambiguous = 0;
        for (OrganizationJournalItem item : items) {
            switch (item.state()) {
                case COMMITTED -> committed++;
                case PLANNED, STAGING -> {
                    if (untouchedOrOnlyStaged(item)) recoverablePending++;
                    else ambiguous++;
                }
                case VALIDATED -> {
                    if (rollbackIsDeterministic(item)) recoverablePending++;
                    else ambiguous++;
                }
                case FAILED -> {
                    if (rollbackIsDeterministic(item)) recoverablePending++;
                    else ambiguous++;
                }
                case SKIPPED, ROLLED_BACK -> { }
            }
        }
        Path manifestPath = LibraryOperationWorkspace.manifestFor(operation.operationId());
        boolean manifest = regular(manifestPath);
        if (manifest && !manifestConsistent(operation, items, manifestPath)) ambiguous++;
        RecoveryDisposition disposition;
        if (ambiguous > 0) disposition = RecoveryDisposition.MANUAL_REVIEW;
        else if (committed > 0 || recoverablePending > 0) disposition = RecoveryDisposition.ROLLBACK_AVAILABLE;
        else disposition = RecoveryDisposition.NOTHING_TO_ROLLBACK;
        return new LibraryOrganizationStatus(operation, items,
                disposition == RecoveryDisposition.ROLLBACK_AVAILABLE,
                disposition == RecoveryDisposition.MANUAL_REVIEW,
                disposition, committed, recoverablePending, ambiguous, manifest);
    }

    private boolean manifestConsistent(OrganizationJournalOperation operation, List<OrganizationJournalItem> items, Path manifestPath) {
        try {
            LibraryOperationCrashSnapshot snapshot = manifestReader.read(manifestPath);
            if (!operation.operationId().equals(snapshot.operationId())) return false;
            if (!samePath(operation.sourceRoot(), snapshot.sourceRoot()) || !samePath(operation.destinationRoot(), snapshot.destinationRoot())) return false;
            if (!safe(operation.profileId()).equals(safe(snapshot.profileId()))) return false;
            if (!operationStateCanLag(operation.state(), snapshot.state())) return false;
            if (snapshot.items().size() != items.size()) return false;
            Map<UUID, LibraryOperationCrashSnapshot.Item> byId = new HashMap<>();
            for (LibraryOperationCrashSnapshot.Item item : snapshot.items()) {
                if (byId.put(item.itemId(), item) != null) return false;
            }
            for (OrganizationJournalItem item : items) {
                LibraryOperationCrashSnapshot.Item fs = byId.get(item.itemId());
                if (fs == null || !itemConsistent(item, fs)) return false;
            }
            return true;
        } catch (Exception invalidManifest) {
            return false;
        }
    }

    /** The DB item is written first, then the filesystem manifest; therefore the manifest may lag, never lead. */
    private boolean itemConsistent(OrganizationJournalItem db, LibraryOperationCrashSnapshot.Item fs) {
        if (!safe(db.action()).equals(safe(fs.action()))) return false;
        if (!samePath(db.sourcePath(), fs.sourcePath()) || !safe(db.sourceEntry()).equals(safe(fs.sourceEntry()))
                || !samePath(db.targetPath(), fs.targetPath())) return false;
        if (!fieldCanLag(db.sourceSha256(), fs.sourceSha256()) || !fieldCanLag(db.targetSha256(), fs.targetSha256())) return false;
        if (fs.backupPath() != null && !samePath(db.backupPath(), fs.backupPath())) return false;
        return itemStateCanLag(db.state(), fs.state());
    }

    private boolean fieldCanLag(String db, String fs) {
        String persisted = safe(db), manifest = safe(fs);
        return manifest.isBlank() || (!persisted.isBlank() && persisted.equalsIgnoreCase(manifest));
    }

    private boolean itemStateCanLag(OrganizationItemState db, OrganizationItemState fs) {
        if (db == fs) return true;
        return switch (db) {
            case PLANNED -> false;
            case STAGING -> fs == OrganizationItemState.PLANNED;
            case VALIDATED -> fs == OrganizationItemState.PLANNED || fs == OrganizationItemState.STAGING;
            case COMMITTED -> fs == OrganizationItemState.PLANNED || fs == OrganizationItemState.STAGING || fs == OrganizationItemState.VALIDATED;
            case SKIPPED -> fs == OrganizationItemState.PLANNED;
            case FAILED -> fs == OrganizationItemState.PLANNED || fs == OrganizationItemState.STAGING || fs == OrganizationItemState.VALIDATED;
            case ROLLED_BACK -> fs != OrganizationItemState.ROLLED_BACK;
        };
    }

    private boolean operationStateCanLag(OrganizationOperationState db, OrganizationOperationState fs) {
        if (db == fs) return true;
        return switch (db) {
            case PLANNED -> false;
            case APPLYING -> fs == OrganizationOperationState.PLANNED;
            case COMPLETED -> fs == OrganizationOperationState.PLANNED || fs == OrganizationOperationState.APPLYING;
            case FAILED -> fs == OrganizationOperationState.PLANNED || fs == OrganizationOperationState.APPLYING;
            case CANCELLED -> fs == OrganizationOperationState.PLANNED || fs == OrganizationOperationState.APPLYING;
            case ROLLED_BACK -> fs != OrganizationOperationState.ROLLED_BACK;
        };
    }

    private boolean samePath(Path left, Path right) {
        if (left == null || right == null) return left == right;
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    private String safe(String value) { return value == null ? "" : value; }

    private boolean untouchedOrOnlyStaged(OrganizationJournalItem item) {
        boolean originalStillPresent = sourceMatches(item);
        boolean separatePublishedTarget = regular(item.targetPath()) && !samePath(item.sourcePath(), item.targetPath());
        return originalStillPresent && !separatePublishedTarget && !regular(item.backupPath());
    }

    /**
     * Determines rollback safety from durable facts only. A published target is still recoverable when
     * its SHA-256 is the exact journaled target hash: Undo can remove/move that known artifact and
     * restore the source/backup, while catalog rollback is idempotent.
     */
    private boolean rollbackIsDeterministic(OrganizationJournalItem item) {
        OrganizationAction action = action(item.action());
        if (action == null) return false;
        boolean source = sourceMatches(item);
        boolean targetFile = regular(item.targetPath());
        boolean sameSourceTarget = samePath(item.sourcePath(), item.targetPath());
        boolean target = targetFile && matches(item.targetPath(), item.targetSha256());
        boolean backup = regular(item.backupPath());
        // For an in-place OPTIMIZE, sourcePath == targetPath. Before final publication the file at that
        // path is still the original and therefore matches sourceSha256 rather than targetSha256.
        if (regular(item.sourcePath()) && !source && !(sameSourceTarget && target)) return false;
        if (targetFile && !target && !(sameSourceTarget && source)) return false;
        if (backup && !matches(item.backupPath(), item.sourceSha256())) return false;

        return switch (action) {
            case RENAME, MOVE, MOVE_AND_RENAME -> target || source;
            case EXTRACT -> (!target || hasHash(item.targetSha256())) && source;
            case CONVERT, CONVERT_AND_MOVE -> {
                // Archive-entry sources are never removed; ordinary sources may require a verified recovery backup.
                yield (!target || hasHash(item.targetSha256())) && (source || (!item.sourceEntry().isBlank() ? false : backup));
            }
            case OPTIMIZE, OPTIMIZE_AND_MOVE ->
                    (!target || hasHash(item.targetSha256())) && (source || (!item.sourceEntry().isBlank() ? false : backup));
            case DELETE_EXACT_DUPLICATE, ARCHIVE_ORIGINAL -> source || backup;
            case NOOP, KEEP_AS_IS, REVIEW_DUPLICATE, SKIP_UNSUPPORTED -> !target;
            case CONFLICT, ERROR -> false;
        };
    }

    private OrganizationAction action(String value) {
        try { return OrganizationAction.valueOf(value == null ? "" : value); }
        catch (RuntimeException ignored) { return null; }
    }

    private boolean regular(Path path) {
        if (path == null) return false;
        try { OrganizerPathSafety.canonicalExistingFile(path, "Organizer recovery evidence"); return true; }
        catch (Exception unsafe) { return false; }
    }
    private boolean hasHash(String value) { return value != null && !value.isBlank(); }

    private boolean matches(Path path, String expected) {
        if (!regular(path) || !hasHash(expected)) return false;
        try { return expected.equalsIgnoreCase(sha256(path)); }
        catch (Exception ignored) { return false; }
    }

    private boolean sourceMatches(OrganizationJournalItem item) {
        if (item == null || item.sourcePath() == null || !regular(item.sourcePath()) || !hasHash(item.sourceSha256())) return false;
        try {
            String actual = item.sourceEntry().isBlank() ? sha256(item.sourcePath()) : sha256ArchiveEntry(item.sourcePath(), item.sourceEntry());
            return item.sourceSha256().equalsIgnoreCase(actual);
        } catch (Exception ignored) { return false; }
    }

    private String sha256ArchiveEntry(Path archive, String sourceEntry) throws Exception {
        if (unsafeArchiveEntryName(sourceEntry)) throw new IllegalStateException("Unsafe archive entry name");
        try (ZipFile zip = SafeZipSupport.openDetected(archive, "Organizer archive")) {
            ZipArchiveEntry selected = null; int inspected = 0;
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                if (++inspected > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IllegalStateException("Archive entry-count limit exceeded");
                ZipArchiveEntry entry = entries.nextElement();
                if (unsafeArchiveEntryName(entry.getName())) throw new IllegalStateException("Archive contains unsafe entry path");
                if (!entry.isDirectory() && sourceEntry.equals(entry.getName())) {
                    if (selected != null) throw new IllegalStateException("Duplicate archive entry name");
                    selected = entry;
                }
            }
            validateArchiveEntry(selected);
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[1024 * 1024]; long total = 0;
            try (InputStream input = zip.getInputStream(selected)) {
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read <= 0) continue; total += read;
                    if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new IllegalStateException("Archive entry oversized");
                    digest.update(buffer, 0, read);
                }
            }
            if (selected.getSize() >= 0 && total != selected.getSize()) throw new IllegalStateException("Archive entry size mismatch");
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private void validateArchiveEntry(ZipArchiveEntry entry) {
        if (entry == null || entry.isDirectory()) throw new IllegalStateException("Archive entry missing or invalid");
        if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) throw new IllegalStateException("Archive entry oversized");
        if (ArchiveSafetyLimits.exceedsCompressionRatio(entry.getSize(), entry.getCompressedSize()))
            throw new IllegalStateException("Archive entry compression ratio exceeds safety limit");
    }

    private boolean unsafeArchiveEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) return true;
        for (String part : name.replace('\\', '/').split("/")) if ("..".equals(part)) return true;
        return false;
    }

    private String sha256(Path path) throws Exception {
        Path safe = OrganizerPathSafety.canonicalExistingFile(path, "Organizer recovery evidence");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream input = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = input.read(buffer)) >= 0; ) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
