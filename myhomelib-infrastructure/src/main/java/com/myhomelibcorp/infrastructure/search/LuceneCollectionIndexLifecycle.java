package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.application.search.SearchIndexHealth;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Owns per-collection Lucene storage selection and DB/WAL freshness markers. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LuceneCollectionIndexLifecycle implements SearchIndexLifecycle {
    private static final long MAX_STATE_FILE_BYTES = 4096L;
    private final LuceneSearchService search;
    private final BookQueryRepository books;

    // Search callbacks and lifecycle transitions share one monitor; never acquire this
    // object and then search in the opposite order to a commit callback.
    private String activeCollectionId;
    private Path activeDatabasePath;
    private Path activeStateFile;
    private String lastClosedCollectionId;
    private Path lastClosedDatabasePath;
    private int lastClosedDocumentCount;
    private boolean activeIndexDirty;
    private boolean lastClosedIndexDirty;

    @PostConstruct
    void registerCommitObserver() {
        search.setCommitObserver(this::onLuceneCommit);
        search.setFullRebuildObserver(this::markCurrentIndexSynchronized);
    }

    @Override
    public boolean activateCollectionIndex(Collection collection) {
        synchronized (search) {
            requireId(collection);
            try {
                Path indexPath = AppPaths.collectionSearchIndexDir(collection.getId());
                SafeFilePublication.createDirectoriesNoLinks(indexPath, "Lucene collection index directory");
                activeCollectionId = collection.getId();
                activeDatabasePath = resolveDatabasePath(collection);
                activeStateFile = AppPaths.collectionSearchIndexStateFile(collection.getId());
                search.switchDirectory(FSDirectory.open(indexPath));

                ReuseCheck check = checkCurrentIndexReusable();
                boolean reusable = check.reusable();
                activeIndexDirty = !reusable;
                if (!reusable) {
                    log.info("Per-collection Lucene {} requires rebuild: {}", indexPath, check.reason());
                    search.setQueryAvailability(false, "Пошуковий індекс перебудовується: " + check.reason());
                    persistDirtyMarker(activeStateFile);
                    // Keep the last committed Lucene index intact until the atomic full rebuild reaches
                    // its final commit. Querying remains disabled while dirty, so the old index cannot
                    // leak stale results, but it remains a crash/failure rollback point.
                } else {
                    search.setQueryAvailability(true, null);
                    log.info("Per-collection Lucene {} is reusable: {} documents", indexPath, search.getDocumentCount());
                }
                return reusable;
            } catch (IOException e) {
                clearActiveState();
                throw new IllegalStateException("Cannot activate Lucene index for collection " + collection.getId(), e);
            }
        }
    }

    @Override
    public SearchIndexHealth currentHealth() {
        synchronized (search) {
            if (activeCollectionId == null || activeDatabasePath == null || activeStateFile == null) {
                return SearchIndexHealth.unknown("active collection index is not initialized");
            }
            if (activeIndexDirty) {
                return new SearchIndexHealth(false, "freshness marker is DIRTY while DB/search synchronization is pending");
            }
            ReuseCheck check = checkCurrentIndexReusable();
            return new SearchIndexHealth(check.reusable(), check.reason());
        }
    }

    @Override
    public void markCurrentIndexDirty() {
        synchronized (search) {
            if (activeCollectionId == null) return;
            activeIndexDirty = true;
            search.setQueryAvailability(false, "Пошуковий індекс синхронізується з каталогом");
            if (activeStateFile == null) {
                throw new IllegalStateException("Cannot persist DIRTY marker without an active Lucene state file");
            }
            persistDirtyMarkerStrict(activeStateFile);
        }
    }

    @Override
    public void markCurrentIndexSynchronized() {
        synchronized (search) {
            if (activeCollectionId == null || activeDatabasePath == null || activeStateFile == null) return;
            activeIndexDirty = false;
            persistMarker(activeStateFile, activeDatabasePath, search.getDocumentCount());
            search.setQueryAvailability(true, null);
        }
    }

    @Override
    public void closeCurrentIndex() {
        synchronized (search) {
            if (activeCollectionId != null) {
                search.commit();
                lastClosedCollectionId = activeCollectionId;
                lastClosedDatabasePath = activeDatabasePath;
                lastClosedDocumentCount = search.getDocumentCount();
                lastClosedIndexDirty = activeIndexDirty;
            }
            search.closeIndexForSwitch();
            clearActiveState();
        }
    }

    @Override
    public void sealClosedIndex(Collection collection) {
        synchronized (search) {
            if (collection == null || collection.getId() == null) return;
            if (!collection.getId().equals(lastClosedCollectionId) || lastClosedDatabasePath == null) return;
            Path stateFile = AppPaths.collectionSearchIndexStateFile(collection.getId());
            if (lastClosedIndexDirty) {
                persistDirtyMarker(stateFile);
            } else {
                persistMarker(stateFile, lastClosedDatabasePath, lastClosedDocumentCount);
            }
            lastClosedCollectionId = null;
            lastClosedDatabasePath = null;
            lastClosedDocumentCount = 0;
            lastClosedIndexDirty = false;
        }
    }

    private void onLuceneCommit(int documentCount) {
        synchronized (search) {
            if (!activeIndexDirty && activeDatabasePath != null && activeStateFile != null) {
                persistMarker(activeStateFile, activeDatabasePath, documentCount);
            }
        }
    }

    private ReuseCheck checkCurrentIndexReusable() {
        if (activeStateFile == null || !Files.exists(activeStateFile, LinkOption.NOFOLLOW_LINKS)) {
            return new ReuseCheck(false, "freshness marker is absent");
        }
        try {
            Path safeMarker = SafeFilePublication.requireSafeRegularFile(
                    activeStateFile, "Lucene freshness marker");
            String persisted = readMarkerBounded(safeMarker);
            if (persisted.equals("DIRTY")) {
                return new ReuseCheck(false, "previous DB/search synchronization was not completed");
            }
            long activeBooks = Math.max(0L, books.countAll());
            int indexedDocuments = search.getDocumentCount();
            if (activeBooks != indexedDocuments) {
                return new ReuseCheck(false, "book/document count mismatch: DB=" + activeBooks + ", Lucene=" + indexedDocuments);
            }
            String current = freshnessToken(activeDatabasePath, activeBooks);
            if (!persisted.equals(current)) {
                return new ReuseCheck(false, "SQLite/WAL freshness state changed since the last clean index seal");
            }
            return new ReuseCheck(true, "freshness marker and document count match");
        } catch (Exception e) {
            log.warn("Cannot validate Lucene freshness marker for {}: {}", activeCollectionId, e.toString());
            return new ReuseCheck(false, "freshness validation failed: " + e.getClass().getSimpleName());
        }
    }

    private record ReuseCheck(boolean reusable, String reason) { }

    private static String readMarkerBounded(Path marker) throws IOException {
        try (InputStream in = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) MAX_STATE_FILE_BYTES + 1);
            if (bytes.length > MAX_STATE_FILE_BYTES) {
                throw new IOException("Lucene freshness marker exceeds safety limit: " + marker);
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        }
    }

    private void persistDirtyMarkerStrict(Path stateFile) {
        try {
            AtomicFileSupport.writeStringReplacing(stateFile, "DIRTY", StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist mandatory dirty Lucene marker " + stateFile, e);
        }
    }

    private void persistDirtyMarker(Path stateFile) {
        try {
            AtomicFileSupport.writeStringReplacing(stateFile, "DIRTY", StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Cannot persist dirty Lucene marker {}: {}", stateFile, e.toString());
        }
    }

    private void persistMarker(Path stateFile, Path databasePath, long documentCount) {
        try {
            AtomicFileSupport.writeStringReplacing(
                    stateFile, freshnessToken(databasePath, documentCount), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Cannot persist Lucene freshness marker {}: {}", stateFile, e.toString());
        }
    }

    private static String freshnessToken(Path databasePath, long activeBooks) throws IOException {
        StringBuilder token = new StringBuilder(224);
        token.append("schema=").append(LuceneDocumentMapper.SCHEMA_VERSION);
        appendFileState(token, databasePath);
        appendWalState(token, Path.of(databasePath + "-wal"));
        token.append("|activeBooks=").append(Math.max(0L, activeBooks));
        return token.toString();
    }

    private static void appendFileState(StringBuilder out, Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        out.append('|').append(normalized);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) { out.append(":missing"); return; }
        Path safe = SafeFilePublication.requireSafeRegularFile(normalized, "Lucene freshness database");
        out.append(':').append(Files.size(safe))
                .append(':').append(Files.getLastModifiedTime(safe, LinkOption.NOFOLLOW_LINKS).toMillis());
    }

    private static void appendWalState(StringBuilder out, Path walPath) throws IOException {
        Path normalized = walPath.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            out.append("|wal:clean");
            return;
        }
        Path safe = SafeFilePublication.requireSafeRegularFile(normalized, "Lucene freshness WAL");
        long size = Files.size(safe);
        if (size == 0L) {
            out.append("|wal:clean");
            return;
        }
        out.append("|wal:").append(size)
                .append(':').append(Files.getLastModifiedTime(safe, LinkOption.NOFOLLOW_LINKS).toMillis());
    }

    private static Path resolveDatabasePath(Collection collection) {
        if (collection.getDbFile() != null && !collection.getDbFile().isBlank()) {
            return Path.of(collection.getDbFile()).toAbsolutePath().normalize();
        }
        return AppPaths.librariesDir().resolve(collection.getId() + ".db").toAbsolutePath().normalize();
    }

    private static void requireId(Collection collection) {
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) {
            throw new IllegalArgumentException("Collection id is required for Lucene index activation");
        }
    }

    private void clearActiveState() {
        activeCollectionId = null;
        activeDatabasePath = null;
        activeStateFile = null;
        activeIndexDirty = false;
    }
}
