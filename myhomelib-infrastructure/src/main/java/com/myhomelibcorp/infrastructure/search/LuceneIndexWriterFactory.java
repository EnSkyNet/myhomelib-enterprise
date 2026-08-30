package com.myhomelibcorp.infrastructure.search;

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
import java.nio.file.Path;

/** Centralized, repeatable IndexWriter/SearcherManager configuration for startup and rollback reopen. */
@Slf4j
final class LuceneIndexWriterFactory {
    private static final int LOCK_ATTEMPTS = 5;

    private LuceneIndexWriterFactory() { }

    static OpenedIndex open(Directory initialDirectory, Analyzer analyzer) {
        Directory directory = initialDirectory;
        IndexWriterConfig config = config(analyzer, IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        Exception lastLock = null;

        for (int attempt = 1; attempt <= LOCK_ATTEMPTS; attempt++) {
            try {
                IndexWriter writer = new IndexWriter(directory, config);
                SearcherManager searcher = new SearcherManager(writer, true, true, null);
                log.info("Lucene IndexWriter/SearcherManager opened (attempt {})", attempt);
                return new OpenedIndex(directory, writer, searcher);
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
                config = config(analyzer, IndexWriterConfig.OpenMode.CREATE);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot open Lucene index", e);
            }
        }
        throw new IllegalStateException("Cannot obtain Lucene index lock after " + LOCK_ATTEMPTS + " attempts", lastLock);
    }

    static IndexWriterConfig config(Analyzer analyzer, IndexWriterConfig.OpenMode mode) {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(mode);
        config.setRAMBufferSizeMB(512.0);
        config.setMaxBufferedDocs(10_000);
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
            Files.createDirectories(path);
            log.warn("Recreated incompatible Lucene index at {}", path);
            return FSDirectory.open(path);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot recreate incompatible Lucene index at " + path, e);
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    record OpenedIndex(Directory directory, IndexWriter writer, SearcherManager searcherManager) { }
}
