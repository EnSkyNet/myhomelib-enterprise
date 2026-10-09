package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace;
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
            requireExpectedCollection(expectedCollectionId);
            List<OrganizationJournalItem>items=new ArrayList<>(journal.items(operationId));Collections.reverse(items);
            for (OrganizationJournalItem item : items) {
                if (!rollbackEligible(item)) continue;
                undoItem(item);
                if (item.state() == OrganizationItemState.COMMITTED || item.state() == OrganizationItemState.VALIDATED
                        || item.state() == OrganizationItemState.FAILED) rollbackCatalog(item);
                journal.updateItem(new OrganizationJournalItem(item.operationId(), item.itemId(), item.sourcePath(), item.sourceEntry(),
                        item.targetPath(), item.sourceSha256(), item.targetSha256(), item.action(),
                        OrganizationItemState.ROLLED_BACK, item.backupPath(), item.optimizationProfile(),
                        item.sizeBefore(), item.sizeAfter(), item.bytesSaved(), item.percentSaved(),
                        item.optimizationSummaryJson(), item.targetStandard(), item.deviceProfile(), item.compressionBackend(),
                        item.validationStatus(), "", ""));
                restored++;
            }
            journal.updateOperation(operationId,OrganizationOperationState.ROLLED_BACK,"{\"rolledBack\":"+restored+"}");
            LibraryOperationWorkspace.deleteTree(LibraryOperationWorkspace.rootFor(operationId));
            lease.markCompleted("Rolled back "+restored+" organizer items");return restored;
        } catch(Exception failure){lease.markFailed(failure);throw failure;} finally {lease.close();}
    }


    private void requireExpectedCollection(String expectedCollectionId) {
        String expected = expectedCollectionId == null ? "" : expectedCollectionId.trim();
        if (expected.isEmpty()) return;
        if (collectionLifecycle == null) {
            throw new IllegalStateException("Cannot verify organizer rollback collection identity");
        }
        var current = collectionLifecycle.getCurrentCollection();
        String actual = current == null || current.getId() == null ? "" : current.getId();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Active collection differs from organizer operation collection");
        }
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
                removeOwnedFile(target,item.targetSha256(),"Organizer rollback target removal");
            }
            if (sourceMatches(item)) {
                if (item.sourceEntry().isBlank()) cleanupRedundantBackup(backup,item.sourceSha256());
                return;
            }
            if (backup != null) { restoreBackup(backup,source,item.sourceSha256()); return; }
            throw new IllegalStateException("Validated rollback has neither source nor backup: "+item.itemId());
        }
        if("EXTRACT".equals(action)){
            if(target!=null&&Files.exists(target, LinkOption.NOFOLLOW_LINKS)){verifyOptionalHash(target,item.targetSha256(),"Rollback hash mismatch");removeOwnedFile(target,item.targetSha256(),"Organizer rollback target removal");}
            if(!sourceMatches(item))throw new IllegalStateException("Archive entry changed before rollback: "+sourceLabel(item));
            return;
        }
        if("DELETE_EXACT_DUPLICATE".equals(action)||"ARCHIVE_ORIGINAL".equals(action)){restoreBackup(backup,source,item.sourceSha256());return;}
        if("RENAME".equals(action)||"MOVE".equals(action)||"MOVE_AND_RENAME".equals(action)){
            if(target==null||!regular(target))throw new IllegalStateException("Rollback target is missing: "+target);verifyOptionalHash(target,item.targetSha256(),"Rollback hash mismatch");if(Files.exists(source, LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(source.toString());Path parent=source.toAbsolutePath().normalize().getParent();if(parent!=null)OrganizerPathSafety.createDirectoriesNoLinks(parent,"Organizer rollback source directory");moveSafe(target,source,item.targetSha256());return;
        }
        if("OPTIMIZE".equals(action)||"OPTIMIZE_AND_MOVE".equals(action)){
            if(target!=null&&Files.exists(target, LinkOption.NOFOLLOW_LINKS)){verifyOptionalHash(target,item.targetSha256(),"Rollback hash mismatch");removeOwnedFile(target,item.targetSha256(),"Organizer rollback target removal");}
            if(!item.sourceEntry().isBlank()){if(!sourceMatches(item))throw new IllegalStateException("Archive entry changed before rollback: "+sourceLabel(item));}
            else restoreBackup(backup,source,item.sourceSha256());return;
        }
        if("CONVERT".equals(action)||"CONVERT_AND_MOVE".equals(action)){
            if(target!=null&&Files.exists(target, LinkOption.NOFOLLOW_LINKS)){verifyOptionalHash(target,item.targetSha256(),"Rollback hash mismatch");removeOwnedFile(target,item.targetSha256(),"Organizer rollback target removal");}
            if(!item.sourceEntry().isBlank()){if(!sourceMatches(item))throw new IllegalStateException("Archive entry changed before rollback: "+sourceLabel(item));}
            else if(backup!=null)restoreBackup(backup,source,item.sourceSha256());return;
        }
    }
    private OrganizationAction action(String value) {
        try { return OrganizationAction.valueOf(value == null ? "" : value); }
        catch (RuntimeException ignored) { return null; }
    }
    private void cleanupRedundantBackup(Path backup,String expected)throws Exception{if(backup!=null&&regular(backup))removeOwnedFile(backup,expected,"Organizer redundant backup removal");}
    private void restoreBackup(Path backup,Path source,String expected)throws Exception{if(backup==null||!regular(backup))throw new IllegalStateException("Rollback backup is missing: "+backup);verifyOptionalHash(backup,expected,"Rollback hash mismatch");if(Files.exists(source, LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(source.toString());Path parent=source.toAbsolutePath().normalize().getParent();if(parent!=null)OrganizerPathSafety.createDirectoriesNoLinks(parent,"Organizer rollback source directory");moveSafe(backup,source,expected);}
    private void moveSafe(Path source,Path target,String expected)throws Exception{
        if(expected==null||expected.isBlank())throw new IllegalStateException("Organizer rollback move expected SHA-256 is missing");
        Path safeSource=OrganizerPathSafety.canonicalExistingFile(source,"Organizer rollback move source");
        verifyOptionalHash(safeSource,expected,"Rollback hash mismatch");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(target.toString());
        try{
            Files.move(safeSource,target);
        } catch(FileSystemException crossVolume){
            OrganizerPathSafety.copyFreshFile(safeSource,target,"Organizer rollback cross-volume copy");
            try {
                verifyOptionalHash(target,expected,"Rollback hash mismatch");
                removeOwnedFile(safeSource,expected,"Organizer rollback cross-volume source removal");
                return;
            } catch(Exception failure) {
                try { removeOwnedFileIfPresent(target,expected,"Organizer rollback failed cross-volume target cleanup"); }
                catch(Exception cleanup) { failure.addSuppressed(cleanup); }
                failure.addSuppressed(crossVolume);
                throw failure;
            }
        }
        try { verifyOptionalHash(target,expected,"Rollback hash mismatch"); }
        catch(Exception changed) {
            try { if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS)) Files.move(target,source); }
            catch(Exception restore) { changed.addSuppressed(restore); }
            throw changed;
        }
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
