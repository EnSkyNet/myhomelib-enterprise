package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.importengine.InpxImportPipeline;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded INPX change aggregation/finalization policy for folder synchronization. */
@Slf4j
final class FolderSyncInpxSupport {
    private FolderSyncInpxSupport() {}

    static FileChanges importAndAccumulate(InpxImportPipeline pipeline, Path file, Path root,
                                           AtomicBoolean cancelFlag, ImportChangeAccumulator accumulated) {
        ImportResult imported = pipeline.importFileWithResult(
                file, 1000, root, cancelFlag, null, null, null, null);
        if (imported.status() == ImportStatus.CANCELLED) return FileChanges.skipped();

        ImportChangeSet changes = imported.changes();
        long changed = totalChanges(changes);
        if (changed > 0 || (!changes.complete() && imported.imported() > 0)) accumulated.merge(changes);
        return new FileChanges(
                boundedInt(changes.insertedCount()), boundedInt(changes.updatedCount()),
                boundedInt(changes.deletedCount()), boundedInt(imported.errors()));
    }

    static Finalization finalizeIndex(ImportChangeSet changes, SearchIndexSynchronizer synchronizer) {
        long changed = totalChanges(changes);
        if (changed == 0) return Finalization.notPerformed();

        if (!changes.complete()) {
            boolean success = synchronizer.rebuildSafelyNow();
            if (success) {
                log.info("Folder sync: one full Lucene rebuild after bounded INPX overflow ({} changes)", changed);
                return Finalization.succeeded();
            }
            return Finalization.failed("Не вдалося перебудувати пошуковий індекс після INPX sync");
        }

        LinkedHashSet<BookId> ids = new LinkedHashSet<>();
        addBookIds(ids, changes.inserted());
        addBookIds(ids, changes.updated());
        addBookIds(ids, changes.deleted());
        boolean success = synchronizer.synchronizeSafelyNow(new ArrayList<>(ids));
        if (success) {
            log.info("Folder sync: selective Lucene synchronization for {} INPX book IDs", ids.size());
            return Finalization.succeeded();
        }
        return Finalization.failed("Не вдалося синхронізувати пошуковий індекс після INPX sync");
    }

    private static long totalChanges(ImportChangeSet changes) {
        return changes.insertedCount() + changes.updatedCount() + changes.deletedCount();
    }

    private static void addBookIds(Set<BookId> target, Set<String> source) {
        for (String id : source) if (id != null && !id.isBlank()) target.add(BookId.fromString(id));
    }

    private static int boundedInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    record FileChanges(int added, int updated, int deleted, int errors) {
        static FileChanges skipped() { return new FileChanges(0, 0, 0, 0); }
    }

    record Finalization(boolean performed, boolean success, String errorMessage) {
        static Finalization notPerformed() { return new Finalization(false, true, ""); }
        static Finalization succeeded() { return new Finalization(true, true, ""); }
        static Finalization failed(String message) { return new Finalization(true, false, message); }
    }
}
