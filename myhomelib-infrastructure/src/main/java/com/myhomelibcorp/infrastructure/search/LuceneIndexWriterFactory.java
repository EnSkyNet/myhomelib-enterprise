package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.search.SearchIndexUnavailableException;
import com.myhomelibcorp.application.util.SafeFilePublication;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Centralized, repeatable IndexWriter/SearcherManager configuration for startup and rollback reopen. */
@Slf4j
final class LuceneIndexWriterFactory {
    private static final int LOCK_ATTEMPTS = 5;
    static final double RAM_BUFFER_SIZE_MB = 256.0;
    static final int MAX_BUFFERED_DOCS = 10_000;

    private LuceneIndexWriterFactory() { }

    static OpenedIndex open(Directory initialDirectory, Analyzer analyzer) {
        Directory directory = initialDirectory;
        IndexWriterConfig.OpenMode mode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND;
        Exception lastLock = null;

        for (int attempt = 1; attempt <= LOCK_ATTEMPTS; attempt++) {
            try {
                // IndexWriterConfig is attached to one writer, including failed open attempts.
                OpenedIndex opened = openResources(directory, analyzer, mode);
                log.info("Lucene IndexWriter/SearcherManager opened (attempt {})", attempt);
                return opened;
            } catch (LockObtainFailedException e) {
                lastLock = e;
                if (attempt == LOCK_ATTEMPTS) break;
                log.warn("Lucene index is locked (attempt {}/{}); retrying", attempt, LOCK_ATTEMPTS);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Lucene lock", interrupted);
                }
            } catch (IndexFormatTooNewException | IndexFormatTooOldException incompatible) {
                directory = recreateFilesystemDirectory(directory, incompatible);
                mode = IndexWriterConfig.OpenMode.CREATE;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot open Lucene index", e);
            }
        }
        try { directory.close(); } catch (IOException closeFailure) {
            if (lastLock != null) lastLock.addSuppressed(closeFailure);
        }
        throw new SearchIndexUnavailableException(
                "Пошуковий індекс зайнятий іншим процесом; бібліотеку можна використовувати без пошуку", lastLock);
    }

    private static OpenedIndex openResources(Directory directory, Analyzer analyzer, IndexWriterConfig.OpenMode mode)
            throws IOException {
        IndexWriter writer = new IndexWriter(directory, config(analyzer, mode));
        try {
            return new OpenedIndex(directory, writer, new SearcherManager(writer, true, true, null));
        } catch (IOException | RuntimeException failure) {
            try { writer.rollback(); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static IndexWriterConfig config(Analyzer analyzer, IndexWriterConfig.OpenMode mode) {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(mode);
        config.setRAMBufferSizeMB(RAM_BUFFER_SIZE_MB);
        config.setMaxBufferedDocs(MAX_BUFFERED_DOCS);
        config.setCommitOnClose(false);
        config.setUseCompoundFile(false);
        TieredMergePolicy mergePolicy = new TieredMergePolicy();
        mergePolicy.setMaxMergeAtOnce(10);
        mergePolicy.setSegmentsPerTier(10);
        mergePolicy.setMaxMergedSegmentMB(5_120);
        mergePolicy.setNoCFSRatio(0.0);
        config.setMergePolicy(mergePolicy);
        return config;
    }

    private static Directory recreateFilesystemDirectory(Directory directory, Exception cause) {
        if (!(directory instanceof FSDirectory fsDirectory)) {
            throw new IllegalStateException("Cannot recreate incompatible non-filesystem Lucene directory", cause);
        }
        Path path = fsDirectory.getDirectory();
        try {
            directory.close();
            deleteDirectory(path);
            Path safePath = SafeFilePublication.createDirectoriesNoLinks(path, "Lucene incompatible-index recreation");
            log.warn("Recreated incompatible Lucene index at {}", safePath);
            return FSDirectory.open(safePath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot recreate incompatible Lucene index at " + path, e);
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Path safeRoot = SafeFilePublication.requireSafeDirectoryPath(
                path, "Lucene incompatible-index cleanup");
        try (var stream = Files.walk(safeRoot)) {
            for (Path item : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                // Files.walk does not follow links by default. A link nested in our index is safe
                // to unlink, but the root and all of its parents were verified immediately above.
                Files.deleteIfExists(item);
            }
        }
    }

    record OpenedIndex(Directory directory, IndexWriter writer, SearcherManager searcherManager) { }
}
