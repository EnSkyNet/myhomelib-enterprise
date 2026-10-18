package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.fs.LibraryOperationCrashSnapshot;
import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationJournalItem;
import com.myhomelibcorp.organizer.journal.OrganizationJournalOperation;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One identity/ordering policy for recovery status AND direct Undo.
 * A checksum-valid filesystem manifest is not authoritative without its SQLite journal.
 * The durable SQLite item may be ahead of the filesystem event log, never the reverse.
 */
final class OrganizerManifestJournalConsistency {
    private OrganizerManifestJournalConsistency() { }

    static boolean matches(OrganizationJournalOperation operation,
                           List<OrganizationJournalItem> items,
                           LibraryOperationCrashSnapshot snapshot) {
        if (operation == null || items == null || snapshot == null) return false;
        if (!operation.operationId().equals(snapshot.operationId())) return false;
        if (!samePath(operation.sourceRoot(), snapshot.sourceRoot())
                || !samePath(operation.destinationRoot(), snapshot.destinationRoot())) return false;
        if (!safe(operation.profileId()).equals(safe(snapshot.profileId()))) return false;
        if (!operationStateCanLag(operation.state(), snapshot.state())) return false;
        if (snapshot.items().size() != items.size()) return false;
        Map<UUID, LibraryOperationCrashSnapshot.Item> byId = new HashMap<>();
        for (LibraryOperationCrashSnapshot.Item item : snapshot.items()) {
            if (byId.put(item.itemId(), item) != null) return false;
        }
        for (OrganizationJournalItem item : items) {
            if (item == null || !operation.operationId().equals(item.operationId())) return false;
            LibraryOperationCrashSnapshot.Item fs = byId.remove(item.itemId());
            if (fs == null || !itemConsistent(item, fs)) return false;
        }
        return byId.isEmpty();
    }

    /** The DB item is written first, then the filesystem manifest; therefore the manifest may lag, never lead. */
    private static boolean itemConsistent(OrganizationJournalItem db, LibraryOperationCrashSnapshot.Item fs) {
        if (!safe(db.action()).equals(safe(fs.action()))) return false;
        if (!samePath(db.sourcePath(), fs.sourcePath()) || !safe(db.sourceEntry()).equals(safe(fs.sourceEntry()))
                || !samePath(db.targetPath(), fs.targetPath())) return false;
        if (!fieldCanLag(db.sourceSha256(), fs.sourceSha256()) || !fieldCanLag(db.targetSha256(), fs.targetSha256())) return false;
        if (fs.backupPath() != null && !samePath(db.backupPath(), fs.backupPath())) return false;
        return itemStateCanLag(db.state(), fs.state());
    }

    private static boolean fieldCanLag(String db, String fs) {
        String persisted = safe(db), manifest = safe(fs);
        return manifest.isBlank() || (!persisted.isBlank() && persisted.equalsIgnoreCase(manifest));
    }

    private static boolean itemStateCanLag(OrganizationItemState db, OrganizationItemState fs) {
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

    private static boolean operationStateCanLag(OrganizationOperationState db, OrganizationOperationState fs) {
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

    private static boolean samePath(Path left, Path right) {
        if (left == null || right == null) return left == right;
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
