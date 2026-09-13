package com.myhomelibcorp.infrastructure.search;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;

import java.io.IOException;

/** Focused close/rollback policy for Lucene resources during collection switch and shutdown. */
final class LuceneIndexResourceCloser {
    private LuceneIndexResourceCloser() { }

    static void close(SearcherManager searcherManager,
                      IndexWriter indexWriter,
                      Directory directory,
                      boolean atomicUpdate,
                      boolean commitBeforeClose,
                      Runnable commitObserver) {
        Exception failure = null;
        if (searcherManager != null) {
            try { searcherManager.close(); }
            catch (IOException | RuntimeException e) { failure = e; }
        }
        if (indexWriter != null) {
            try {
                if (atomicUpdate) {
                    indexWriter.rollback();
                } else {
                    if (commitBeforeClose) {
                        indexWriter.commit();
                        if (commitObserver != null) commitObserver.run();
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            // A failed commit/observer must not skip releasing the writer and its file lock.
            if (!atomicUpdate) {
                try { indexWriter.close(); } // commitOnClose=false in LuceneIndexWriterFactory
                catch (IOException | RuntimeException e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
        }
        if (directory != null) {
            try { directory.close(); }
            catch (IOException | RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw new IllegalStateException("Не вдалося закрити ресурси Lucene", failure);
    }
}
