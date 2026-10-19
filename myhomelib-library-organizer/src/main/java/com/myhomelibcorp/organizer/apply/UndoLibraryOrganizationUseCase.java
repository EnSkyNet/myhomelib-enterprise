package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace;
import com.myhomelibcorp.organizer.fs.LibraryOperationCrashManifestReader;
import com.myhomelibcorp.organizer.fs.LibraryOperationCrashSnapshot;
import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import com.myhomelibcorp.organizer.journal.*;
import com.myhomelibcorp.organizer.plan.OrganizationAction;
import com.myhomelibcorp.organizer.plan.OrganizationPlanItem;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;
import static com.myhomelibcorp.organizer.apply.OrganizerFileIntegritySupport.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

@Service
public final class UndoLibraryOrganizationUseCase {
    private final OrganizationJournalPort journal;
    private final LibraryOperationCoordinator operations;
    private final List<OrganizerCatalogPort> catalogs;
    private final CollectionLifecyclePort collectionLifecycle;

    /** Compatibility constructor for collection-independent tests/callers. */
    public UndoLibraryOrganizationUseCase(OrganizationJournalPort journal, LibraryOperationCoordinator operations,
                                          List<OrganizerCatalogPort> catalogs) {
        this(journal, operations, catalogs, null);
    }

    @Autowired
    public UndoLibraryOrganizationUseCase(OrganizationJournalPort journal, LibraryOperationCoordinator operations,
                                          List<OrganizerCatalogPort> catalogs, CollectionLifecyclePort collectionLifecycle) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.catalogs = catalogs == null ? List.of() : List.copyOf(catalogs);
        this.collectionLifecycle = collectionLifecycle;
    }

    public int undo(UUID operationId) throws Exception {
        return undo(operationId, "");
    }

    public int undo(UUID operationId, String expectedCollectionId)throws Exception {
        Objects.requireNonNull(operationId,"operationId");int restored=0;LibraryOperationCoordinator.Lease lease=operations.acquireDetached(LibraryOperationType.ORGANIZE);
        try {
            String verifiedCollectionId = requireExpectedCollection(operationId, expectedCollectionId);
            OrganizationJournalOperation expectedOperation = journal.findOperation(operationId)
                    .orElseThrow(() -> new IllegalStateException("Organizer operation disappeared before rollback"));
            List<OrganizationJournalItem>items=new ArrayList<>(journal.items(operationId));
            // Remember whether a crash manifest was present: disappearance or a
            // newly introduced manifest during filesystem rollback is not safe.
            boolean manifestPresentAtStart = Files.exists(
                    LibraryOperationWorkspace.manifestFor(operationId), LinkOption.NOFOLLOW_LINKS);
            verifyCrashManifestBeforeUndo(operationId, items);
            Collections.reverse(items);
            // Loading the journal can race an out-of-band collection switch. The
            // coordinator excludes normal SWITCH use cases, but not every legacy adapter.
            requireActiveCollectionUnchanged(verifiedCollectionId);
            for (OrganizationJournalItem item : items) {
                if (!rollbackEligible(item)) continue;
                requireActiveCollectionUnchanged(verifiedCollectionId);
                requireJournalItemUnchanged(item);
                undoItem(item);
                requireActiveCollectionUnchanged(verifiedCollectionId);
                if (item.state() == OrganizationItemState.COMMITTED || item.state() == OrganizationItemState.VALIDATED
                        || item.state() == OrganizationItemState.FAILED) rollbackCatalog(item);
                requireActiveCollectionUnchanged(verifiedCollectionId);
                requireJournalItemUnchanged(item);
                OrganizationJournalItem rolledBack = item.withOutcome(
                        OrganizationItemState.ROLLED_BACK, "", "", "");
                if (!journal.updateItemIfUnchanged(item, rolledBack)) {
                    throw new IllegalStateException(
                            "Organizer journal changed during rollback: conditional item update rejected");
                }
                restored++;
            }
            requireActiveCollectionUnchanged(verifiedCollectionId);
            // A writer outside the operation coordinator can mutate or remove a
            // previously trusted manifest while an Undo item is being restored.
            // Reconcile against CURRENT durable rows before marking the whole
            // operation ROLLED_BACK and deleting recovery evidence.
            boolean manifestPresentAtEnd = Files.exists(
                    LibraryOperationWorkspace.manifestFor(operationId), LinkOption.NOFOLLOW_LINKS);
            if (manifestPresentAtStart != manifestPresentAtEnd) {
                throw new IllegalStateException("Organizer crash manifest changed during rollback; manual review required");
            }
            verifyCrashManifestBeforeUndo(operationId, journal.items(operationId));
            List<UUID> expectedItemIds = items.stream().map(OrganizationJournalItem::itemId).toList();
            if (!journal.finalizeRollbackIfUnchanged(expectedOperation, expectedItemIds,
                    "{\"rolledBack\":"+restored+"}")) {
                throw new IllegalStateException("Organizer journal changed during rollback: "
                        + "atomic finalization rejected (item set, state or operation identity)");
            }
            LibraryOperationWorkspace.deleteTree(LibraryOperationWorkspace.rootFor(operationId));
            lease.markCompleted("Rolled back "+restored+" organizer items");return restored;
        } catch(Exception failure){lease.markFailed(failure);throw failure;} finally {lease.close();}
    }


    /**
     * A direct Undo invocation must not bypass the strict manifest validation used
     * by recovery status. In particular, a torn event-log tail is evidence of an
     * interrupted update, even if SQLite still describes a plausible rollback.
     * Historical operations without a manifest retain the existing compatibility path.
     */
    private void verifyCrashManifestBeforeUndo(UUID operationId,
                                               List<OrganizationJournalItem> items) throws Exception {
        Path path = LibraryOperationWorkspace.manifestFor(operationId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            LibraryOperationCrashSnapshot snapshot = new LibraryOperationCrashManifestReader().read(path);
            OrganizationJournalOperation recorded = journal.findOperation(operationId)
                    .orElseThrow(() -> new IllegalStateException("Organizer operation record is missing"));
            if (!OrganizerManifestJournalConsistency.matches(recorded, items, snapshot)) {
                throw new IllegalStateException("Organizer manifest differs from the recorded operation or items");
            }
        } catch (Exception untrusted) {
            throw new IllegalStateException(
                    "Organizer crash manifest requires manual review before rollback", untrusted);
        }
    }

    /**
     * Detect stale in-memory journal rows after callbacks or other uncoordinated writers.
     * This is a boundary guard, NOT a CAS transaction across SQLite and the filesystem.
     */
    private void requireJournalItemUnchanged(OrganizationJournalItem expected) {
        OrganizationJournalItem current = journal.findItem(expected.operationId(), expected.itemId())
                .orElseThrow(() -> new IllegalStateException("Organizer journal changed during rollback: item missing"));
        if (!expected.equals(current)) {
            throw new IllegalStateException("Organizer journal changed during rollback: item state or identity differs");
        }
    }

    private String requireExpectedCollection(UUID operationId, String expectedCollectionId) {
        OrganizationJournalOperation recorded = journal.findOperation(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot verify organizer rollback: authoritative operation record is missing"));
        String stored = recorded.collectionId();
        String supplied = expectedCollectionId == null ? "" : expectedCollectionId.trim();
        if (!stored.isBlank()) {
            if (!supplied.isEmpty() && !stored.equals(supplied)) {
                throw new IllegalStateException("Organizer rollback collection differs from journal collection");
            }
            if (collectionLifecycle == null) {
                throw new IllegalStateException("Cannot verify organizer rollback against active collection");
            }
            String active = currentCollectionId();
            if (!stored.equals(active)) {
                throw new IllegalStateException("Active collection differs from organizer operation collection");
            }
            return stored;
        }
        // Old records without durable collection identity are ambiguous. In production,
        // fail closed instead of trusting an arbitrary collection id from the UI.
        if (collectionLifecycle != null) {
            throw new IllegalStateException("Organizer rollback journal has no verified collection identity; manual review required");
        }
        if (!supplied.isEmpty()) {
            throw new IllegalStateException("Cannot verify organizer rollback collection identity");
        }
        return stored;
    }

    /** Fail closed if a collection changes outside the coordinated lifecycle barrier.
     * This is a best-effort boundary check, not an atomic transaction spanning files and SQLite. */
    private void requireActiveCollectionUnchanged(String verifiedCollectionId) {
        if (collectionLifecycle != null && !verifiedCollectionId.equals(currentCollectionId())) {
            throw new IllegalStateException("Active collection changed during organizer rollback");
        }
    }

    private String currentCollectionId() {
        var current = collectionLifecycle.getCurrentCollection();
        return current == null || current.getId() == null ? "" : current.getId();
    }


    private void rollbackCatalog(OrganizationJournalItem item) throws Exception {
        OrganizationAction action;
        try {
            action = OrganizationAction.valueOf(item.action());
        } catch (Exception ignored) {
            return;
        }
        OrganizationPlanItem planItem = new OrganizationPlanItem(item.itemId(), item.sourcePath(), item.sourceEntry(), item.targetPath(),
                "", "", action, action == OrganizationAction.CONVERT || action == OrganizationAction.CONVERT_AND_MOVE,
                null, 0, List.of(), List.of());
        for (OrganizerCatalogPort catalog : catalogs) {
            if (catalog.supports(planItem)) {
                catalog.rollbackCatalog(planItem);
                return;
            }
        }
    }
    private boolean rollbackEligible(OrganizationJournalItem item) {
        return item.state() == OrganizationItemState.COMMITTED
                || item.state() == OrganizationItemState.VALIDATED
                || item.state() == OrganizationItemState.STAGING
                || item.state() == OrganizationItemState.PLANNED
                || item.state() == OrganizationItemState.FAILED;
    }

    private void undoItem(OrganizationJournalItem item)throws Exception {
        String action=item.action()==null?"":item.action();Path source=item.sourcePath(),target=item.targetPath(),backup=item.backupPath();
        if (item.state() == OrganizationItemState.PLANNED || item.state() == OrganizationItemState.STAGING) {
            boolean sameSourceTarget = samePath(source, target);
            if (target != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !sameSourceTarget)
                throw new IllegalStateException("Pending rollback target already exists: "+target);
            if (!sourceMatches(item)) throw new IllegalStateException("Pending rollback source is missing or changed: "+sourceLabel(item));
            return;
        }
        if (item.state() == OrganizationItemState.VALIDATED || item.state() == OrganizationItemState.FAILED) {
            OrganizationAction pendingAction = action(action);
            if (pendingAction == null) throw new IllegalStateException("Unknown pending rollback action: "+action);
            if (target != null && regular(target)) {
                boolean sameSourceTarget = samePath(source, target);
                boolean originalStillPresent = sameSourceTarget && sourceMatches(item);
                if (!originalStillPresent) verifyOptionalHash(target,item.targetSha256(),"Rollback hash mismatch");
                if (originalStillPresent) {
                    // Crash occurred after VALIDATED journal state but before in-place publication.
                    // The source is already the expected original; only a redundant backup may exist.
                    cleanupRedundantBackup(backup,item.sourceSha256());
                    return;
                }
                if (pendingAction == OrganizationAction.RENAME || pendingAction == OrganizationAction.MOVE
                        || pendingAction == OrganizationAction.MOVE_AND_RENAME) {
                    if (source != null && regular(source)) {
                        verifyOptionalHash(source,item.sourceSha256(),"Rollback hash mismatch");
                        removeOwnedFile(target,item.targetSha256(),"Organizer rollback target removal");
                    } else {
                        if (source == null) throw new IllegalStateException("Pending rollback source path is missing");
                        Path parent=source.toAbsolutePath().normalize().getParent();if(parent!=null)OrganizerPathSafety.createDirectoriesNoLinks(parent,"Organizer rollback source directory");
                        moveSafe(target,source,item.targetSha256());
                        verifyOptionalHash(source,item.sourceSha256(),"Rollback hash mismatch");
                    }
                    cleanupRedundantBackup(backup,item.sourceSha256());
                    return;
                }
                // Prove that an original or backup can be restored BEFORE deleting
                // the only currently accessible converted/optimized artifact.
                if (!sourceMatches(item)) {
                    requireVerifiedBackup(backup, item.sourceSha256(), item.itemId());
                }
                removeOwnedFile(target,item.targetSha256(),"Organizer rollback target removal");
            }
            if (sourceMatches(item)) {
                if (item.sourceEntry().isBlank()) cleanupRedundantBackup(backup,item.sourceSha256());
                return;
            }
            if (backup != null) { restoreBackup(backup,source,item.sourceSha256()); return; }
            throw new IllegalStateException("Validated rollback has neither source nor backup: "+item.itemId());
        }
        if ("EXTRACT".equals(action)) {
            if (!sourceMatches(item)) {
                throw new IllegalStateException("Archive entry changed before rollback: " + sourceLabel(item));
            }
            if (target != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                removeOwnedFile(target, item.targetSha256(), "Organizer rollback target removal");
            }
            return;
        }
        if("DELETE_EXACT_DUPLICATE".equals(action)||"ARCHIVE_ORIGINAL".equals(action)) {
            if (sourceMatches(item)) {
                cleanupRedundantBackup(backup, item.sourceSha256());
            } else {
                requireVerifiedBackup(backup, item.sourceSha256(), item.itemId());
                restoreBackup(backup, source, item.sourceSha256());
            }
            return;
        }
        if("RENAME".equals(action)||"MOVE".equals(action)||"MOVE_AND_RENAME".equals(action)){
            // After a crash between restoring the file and recording ROLLED_BACK, a
            // verified source with no target means the rollback already succeeded.
            if ((target == null || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) && sourceMatches(item)) return;
            if (target == null || !regular(target)) throw new IllegalStateException("Rollback target is missing: " + target);
            verifyRequiredHash(target, item.targetSha256(), "Rollback hash mismatch");
            if (source == null) throw new IllegalStateException("Rollback source path is missing");
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(source.toString());
            Path parent = source.toAbsolutePath().normalize().getParent();
            if (parent != null) OrganizerPathSafety.createDirectoriesNoLinks(parent, "Organizer rollback source directory");
            moveSafe(target, source, item.targetSha256());
            return;
        }
        if ("OPTIMIZE".equals(action) || "OPTIMIZE_AND_MOVE".equals(action)) {
            // Never remove the optimized result before proving the original survives.
            boolean archiveEntry = !item.sourceEntry().isBlank();
            boolean alreadyRestored = sourceMatches(item);
            // For in-place processing source and target are the same pathname.
            // If it already contains the original bytes, never delete it as a
            // supposed output, even when source and target hashes coincide.
            if (alreadyRestored && samePath(source, target)) {
                cleanupRedundantBackup(backup, item.sourceSha256());
                return;
            }
            if (archiveEntry && !alreadyRestored) {
                throw new IllegalStateException("Archive entry changed before rollback: " + sourceLabel(item));
            }
            if (!archiveEntry && !alreadyRestored) {
                requireVerifiedBackup(backup, item.sourceSha256(), item.itemId());
            }
            if (target != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                removeOwnedFile(target, item.targetSha256(), "Organizer rollback target removal");
            }
            if (!archiveEntry && !alreadyRestored) restoreBackup(backup, source, item.sourceSha256());
            else if (!archiveEntry && alreadyRestored) cleanupRedundantBackup(backup, item.sourceSha256());
            return;
        }
        if ("CONVERT".equals(action) || "CONVERT_AND_MOVE".equals(action)) {
            boolean archiveEntry = !item.sourceEntry().isBlank();
            boolean alreadyRestored = sourceMatches(item);
            // For in-place processing source and target are the same pathname.
            // If it already contains the original bytes, never delete it as a
            // supposed output, even when source and target hashes coincide.
            if (alreadyRestored && samePath(source, target)) {
                cleanupRedundantBackup(backup, item.sourceSha256());
                return;
            }
            if (archiveEntry && !alreadyRestored) {
                throw new IllegalStateException("Archive entry changed before rollback: " + sourceLabel(item));
            }
            if (!archiveEntry && !alreadyRestored) {
                requireVerifiedBackup(backup, item.sourceSha256(), item.itemId());
            }
            if (target != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                removeOwnedFile(target, item.targetSha256(), "Organizer rollback target removal");
            }
            if (!archiveEntry && !alreadyRestored) restoreBackup(backup, source, item.sourceSha256());
            else if (!archiveEntry && alreadyRestored) cleanupRedundantBackup(backup, item.sourceSha256());
            return;
        }
        throw new IllegalStateException("Unsupported organizer rollback action: " + action);
    }
    private OrganizationAction action(String value) {
        try { return OrganizationAction.valueOf(value == null ? "" : value); }
        catch (RuntimeException ignored) { return null; }
    }
    private void requireVerifiedBackup(Path backup, String expected, UUID itemId) throws Exception {
        if (backup == null || !regular(backup)) {
            throw new IllegalStateException("Rollback has no verified original or backup: " + itemId);
        }
        verifyRequiredHash(backup, expected, "Rollback backup hash mismatch");
    }
    private void cleanupRedundantBackup(Path backup,String expected)throws Exception{if(backup!=null&&regular(backup))removeOwnedFile(backup,expected,"Organizer redundant backup removal");}
    private void restoreBackup(Path backup,Path source,String expected)throws Exception{if(backup==null||!regular(backup))throw new IllegalStateException("Rollback backup is missing: "+backup);verifyOptionalHash(backup,expected,"Rollback hash mismatch");if(Files.exists(source, LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(source.toString());Path parent=source.toAbsolutePath().normalize().getParent();if(parent!=null)OrganizerPathSafety.createDirectoriesNoLinks(parent,"Organizer rollback source directory");moveSafe(backup,source,expected);}
    /**
     * Restore by publishing a verified full copy under an atomically reserved fresh
     * name, and only then removing the old verified file. A simple Files.move
     * after Files.exists is NOT a safe no-clobber operation under concurrency.
     * On providers without hard links, publication fails closed: the old file
     * remains available for manual recovery instead of risking a foreign target.
     */
    private void moveSafe(Path source, Path target, String expected) throws Exception {
        if (expected == null || !expected.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalStateException("Organizer rollback move expected SHA-256 is missing or invalid");
        }
        Path safeSource = OrganizerPathSafety.canonicalExistingFile(source, "Organizer rollback move source");
        verifyRequiredHash(safeSource, expected, "Rollback source hash mismatch");
        new OrganizerTargetPublisher().publish(safeSource, target, expected);
        // If a concurrent modification makes source removal unsafe, retain the
        // published copy as recovery evidence; never delete an unverified file.
        removeOwnedFile(safeSource, expected, "Organizer rollback verified source removal");
    }

    private boolean sourceMatches(OrganizationJournalItem item){
        try {
            if(item==null||item.sourcePath()==null||!regular(item.sourcePath()))return false;
            String actual=item.sourceEntry().isBlank()?sha256(item.sourcePath()):OrganizerArchiveSupport.sha256(item.sourcePath(),item.sourceEntry());
            return item.sourceSha256()!=null&&!item.sourceSha256().isBlank()&&item.sourceSha256().equalsIgnoreCase(actual);
        }catch(Exception ignored){return false;}
    }
    private boolean regular(Path path){
        if(path==null)return false;
        try{OrganizerPathSafety.canonicalExistingFile(path,"Organizer rollback file");return true;}
        catch(Exception ignored){return false;}
    }
    private boolean samePath(Path left, Path right){return left!=null&&right!=null&&left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());}
    private String sourceLabel(OrganizationJournalItem item){return item.sourceEntry().isBlank()?String.valueOf(item.sourcePath()):item.sourcePath()+"!/"+item.sourceEntry();}
}
