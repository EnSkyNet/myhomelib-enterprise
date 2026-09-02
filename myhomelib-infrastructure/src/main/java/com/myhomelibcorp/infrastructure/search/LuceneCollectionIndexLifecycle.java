package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Owns per-collection Lucene storage selection and DB/WAL freshness markers. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LuceneCollectionIndexLifecycle implements SearchIndexLifecycle {
    private final LuceneSearchService search;
    private final BookQueryRepository books;

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
    public synchronized boolean activateCollectionIndex(Collection collection) {
        requireId(collection);
        try {
            Path indexPath = AppPaths.collectionSearchIndexDir(collection.getId());
            Files.createDirectories(indexPath);
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
                if (search.getDocumentCount() > 0) search.clearIndex();
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

    @Override
    public synchronized void markCurrentIndexDirty() {
        if (activeCollectionId == null) return;
        activeIndexDirty = true;
        search.setQueryAvailability(false, "Пошуковий індекс синхронізується з каталогом");
        if (activeStateFile != null) persistDirtyMarker(activeStateFile);
    }

    @Override
    public synchronized void markCurrentIndexSynchronized() {
        if (activeCollectionId == null || activeDatabasePath == null || activeStateFile == null) return;
        activeIndexDirty = false;
        persistMarker(activeStateFile, activeDatabasePath, search.getDocumentCount());
        search.setQueryAvailability(true, null);
    }

    @Override
    public synchronized void closeCurrentIndex() {
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

    @Override
    public synchronized void sealClosedIndex(Collection collection) {
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

    private void onLuceneCommit(int documentCount) {
        synchronized (this) {
            if (!activeIndexDirty && activeDatabasePath != null && activeStateFile != null) {
                persistMarker(activeStateFile, activeDatabasePath, documentCount);
            }
        }
    }

    private ReuseCheck checkCurrentIndexReusable() {
        if (activeStateFile == null || !Files.isRegularFile(activeStateFile)) {
            return new ReuseCheck(false, "freshness marker is absent");
        }
        try {
            String persisted = Files.readString(activeStateFile, StandardCharsets.UTF_8).trim();
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

    private void persistDirtyMarker(Path stateFile) {
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, "DIRTY", StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Cannot persist dirty Lucene marker {}: {}", stateFile, e.toString());
            try { Files.deleteIfExists(stateFile); } catch (IOException ignored) { }
        }
    }

    private void persistMarker(Path stateFile, Path databasePath, long documentCount) {
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, freshnessToken(databasePath, documentCount), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Cannot persist Lucene freshness marker {}: {}", stateFile, e.toString());
            try { Files.deleteIfExists(stateFile); } catch (IOException ignored) { }
        }
    }

    private static String freshnessToken(Path databasePath, long activeBooks) throws IOException {
        StringBuilder token = new StringBuilder(192);
        appendFileState(token, databasePath);
        appendWalState(token, Path.of(databasePath + "-wal"));
        token.append("|activeBooks=").append(Math.max(0L, activeBooks));
        return token.toString();
    }

    private static void appendFileState(StringBuilder out, Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        out.append('|').append(normalized);
        if (!Files.exists(normalized)) { out.append(":missing"); return; }
        out.append(':').append(Files.size(normalized))
                .append(':').append(Files.getLastModifiedTime(normalized).toMillis());
    }

    private static void appendWalState(StringBuilder out, Path walPath) throws IOException {
        Path normalized = walPath.toAbsolutePath().normalize();
        if (!Files.exists(normalized) || Files.size(normalized) == 0L) {
            out.append("|wal:clean");
            return;
        }
        out.append("|wal:").append(Files.size(normalized))
                .append(':').append(Files.getLastModifiedTime(normalized).toMillis());
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
