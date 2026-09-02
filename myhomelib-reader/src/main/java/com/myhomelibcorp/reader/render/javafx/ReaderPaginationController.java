package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.model.PageLayout;
import javafx.application.Platform;

import java.util.OptionalLong;

/** Incrementally builds an exact compact page index without blocking one JavaFX pulse for the whole book. */
final class ReaderPaginationController {
    private static final long PULSE_BUDGET_NANOS = 8_000_000L;

    private final ReaderEngine engine;
    private final ReaderPageIndex index = new ReaderPageIndex();
    private ReaderDocument document;
    private PageDimensions dimensions;
    private long generation;
    private boolean buildScheduled;
    private Runnable onProgress = () -> { };

    ReaderPaginationController(ReaderEngine engine) {
        this.engine = engine;
    }

    void prepare(PageDimensions requestedDimensions, Runnable progressCallback) {
        if (!engine.isOpen() || engine.getCurrentDocument() == null
                || requestedDimensions == null || !requestedDimensions.isValid()) return;
        if (document != engine.getCurrentDocument() || dimensions == null || !dimensions.equals(requestedDimensions)) {
            reset();
            document = engine.getCurrentDocument();
            dimensions = requestedDimensions;
            index.appendStart(0);
        }
        onProgress = progressCallback != null ? progressCallback : () -> { };
        schedule();
    }

    void invalidate() {
        reset();
        document = engine.isOpen() ? engine.getCurrentDocument() : null;
    }

    void close() {
        reset();
        document = null;
    }

    int pageForOffset(long offset) {
        return index.pageForOffset(offset);
    }

    int totalPages() {
        return index.totalPages();
    }

    OptionalLong offsetForPage(int page) {
        return index.hasPage(page) ? OptionalLong.of(index.offsetForPage(page)) : OptionalLong.empty();
    }

    private void reset() {
        generation++;
        index.reset();
        dimensions = null;
        buildScheduled = false;
    }

    private void schedule() {
        if (index.isComplete() || buildScheduled) return;
        long expectedGeneration = generation;
        buildScheduled = true;
        Platform.runLater(() -> buildPulse(expectedGeneration));
    }

    private void buildPulse(long expectedGeneration) {
        buildScheduled = false;
        if (expectedGeneration != generation || !engine.isOpen() || engine.getCurrentDocument() != document
                || dimensions == null) return;

        long totalText = document.totalTextLength();
        if (totalText <= 0) {
            index.markComplete();
            onProgress.run();
            return;
        }

        long deadline = System.nanoTime() + PULSE_BUDGET_NANOS;
        do {
            long startOffset = Math.max(0, index.lastStart());
            PageLayout page = engine.getPageAt(startOffset, dimensions);
            long endOffset = page == null ? startOffset : page.getEndOffset();
            if (endOffset <= startOffset || endOffset >= totalText) {
                index.markComplete();
                break;
            }
            index.appendStart(endOffset);
        } while (System.nanoTime() < deadline && !index.isComplete());

        onProgress.run();
        schedule();
    }
}
