package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ParagraphInfo;
import com.myhomelibcorp.reader.api.ReaderSelection;
import com.myhomelibcorp.reader.api.ReaderTheme;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;

import java.util.Optional;

/**
 * Owns selection offsets, hit-testing, overlay rendering and clipboard copying.
 * Source text offsets are preserved; no visual selection state leaks into the
 * reader engine or page-navigation state.
 */
final class ReaderSelectionController {
    private static final double HANDLE_RADIUS = 5.0;
    private static final double HANDLE_HIT_RADIUS = 13.0;
    private static final int CONTEXT_CHARS = 64;

    private final ReaderEngine engine;
    private final JavaFxReaderRenderer renderer;

    private boolean selecting;
    private long anchorOffset = -1;
    private long focusOffset = -1;

    ReaderSelectionController(ReaderEngine engine, JavaFxReaderRenderer renderer) {
        this.engine = engine;
        this.renderer = renderer;
    }

    boolean isSelecting() {
        return selecting;
    }

    boolean hasSelection() {
        return anchorOffset >= 0 && focusOffset >= 0 && anchorOffset != focusOffset;
    }

    void begin(double x, double y, PageDimensions dimensions) {
        begin(x, y, engine.getCurrentPage(dimensions), 0.0);
    }

    void begin(double x, double y, PageLayout page, double xOffset) {
        selecting = true;
        long offset = hitTestOffset(x, y, page, xOffset);
        anchorOffset = offset;
        focusOffset = offset;
    }

    /** Starts dragging one of the visible selection handles, preserving the opposite endpoint. */
    boolean beginHandleDrag(double x, double y, PageLayout page, double xOffset) {
        if (!hasSelection() || page == null || page.isEmpty()) return false;
        long from = Math.min(anchorOffset, focusOffset);
        long to = Math.max(anchorOffset, focusOffset);
        HandlePoint start = handlePoint(from, page, xOffset).orElse(null);
        HandlePoint end = handlePoint(to, page, xOffset).orElse(null);
        double startDistance = distance(x, y, start);
        double endDistance = distance(x, y, end);
        if (Math.min(startDistance, endDistance) > HANDLE_HIT_RADIUS) return false;

        selecting = true;
        if (startDistance <= endDistance) {
            anchorOffset = to;
            focusOffset = from;
        } else {
            anchorOffset = from;
            focusOffset = to;
        }
        return true;
    }

    void drag(double x, double y, PageDimensions dimensions) {
        if (!selecting) return;
        focusOffset = hitTestOffset(x, y, engine.getCurrentPage(dimensions), 0.0);
    }

    void drag(double x, double y, PageLayout page, double xOffset) {
        if (!selecting) return;
        focusOffset = hitTestOffset(x, y, page, xOffset);
    }

    void finish(double x, double y, PageDimensions dimensions) {
        if (!selecting) return;
        focusOffset = hitTestOffset(x, y, engine.getCurrentPage(dimensions), 0.0);
        selecting = false;
    }

    void finish(double x, double y, PageLayout page, double xOffset) {
        if (!selecting) return;
        focusOffset = hitTestOffset(x, y, page, xOffset);
        selecting = false;
    }

    /** Keyboard selection uses source-text offsets and therefore survives pagination/layout changes. */
    boolean extendByCharacters(int delta) {
        if (!engine.isOpen() || engine.getCurrentDocument() == null || delta == 0) return false;
        int length = engine.getCurrentDocument().text().length();
        if (length <= 0) return false;
        if (anchorOffset < 0 || focusOffset < 0) {
            long current = engine.getCurrentPosition() == null ? 0 : engine.getCurrentPosition().textOffset();
            anchorOffset = clamp(current, 0, length);
            focusOffset = anchorOffset;
        }
        focusOffset = clamp(focusOffset + delta, 0, length);
        selecting = false;
        return hasSelection();
    }

    Optional<ReaderSelection> snapshot() {
        if (!hasSelection() || engine.getCurrentDocument() == null) return Optional.empty();
        var document = engine.getCurrentDocument();
        int length = document.text().length();
        int a = (int) clamp(Math.min(anchorOffset, focusOffset), 0, length);
        int b = (int) clamp(Math.max(anchorOffset, focusOffset), a, length);
        if (b <= a) return Optional.empty();
        String selected = document.text().getText(a, b);
        if (selected == null || selected.isBlank()) return Optional.empty();

        int chapterIndex = document.chapterIndexAt(a);
        ChapterIndex chapter = document.chapter(chapterIndex);
        ParagraphInfo paragraph = document.text().findParagraphAt(a);
        int paragraphIndex = paragraph == null ? -1 : paragraph.index();
        String paragraphId = paragraphIndex < 0 ? null : "p:" + paragraphIndex;
        int prefixStart = Math.max(0, a - CONTEXT_CHARS);
        int suffixEnd = Math.min(length, b + CONTEXT_CHARS);
        String prefix = document.text().getText(prefixStart, a);
        String suffix = document.text().getText(b, suffixEnd);
        long selectableSpan = Math.max(1L, (long) length - (b - a));
        double position = Math.max(0.0, Math.min(1.0, a / (double) selectableSpan));

        return Optional.of(new ReaderSelection(
                a,
                b,
                selected,
                chapterIndex,
                chapter == null ? null : chapter.id(),
                chapter == null ? null : chapter.title(),
                paragraphIndex,
                paragraphId,
                position,
                prefix,
                suffix));
    }

    void clear() {
        anchorOffset = -1;
        focusOffset = -1;
        selecting = false;
    }

    void renderOverlay(PageLayout page) { renderOverlay(page, 0.0); }

    void renderOverlay(PageLayout page, double xOffset) {
        if (!hasSelection() || page == null || page.isEmpty()) return;
        long from = Math.min(anchorOffset, focusOffset);
        long to = Math.max(anchorOffset, focusOffset);
        var gc = renderer.getGraphicsContext();
        ReaderTheme theme = ReaderTheme.fromSettings(engine.getSettings());
        gc.setFill(Color.web(theme.selectionColor(), 0.38));
        for (LineLayout line : page.getLines()) {
            long lineStart = line.textOffset();
            long lineEnd = lineStart + Math.max(1, line.charLength());
            long a = Math.max(from, lineStart);
            long b = Math.min(to, lineEnd);
            if (b <= a) continue;
            double span = Math.max(1, lineEnd - lineStart);
            double x1 = xOffset + line.x() + line.width() * ((a - lineStart) / span);
            double x2 = xOffset + line.x() + line.width() * ((b - lineStart) / span);
            gc.fillRect(x1, line.y(), Math.max(1, x2 - x1), Math.max(1, line.height()));
        }
        renderHandle(from, page, xOffset, theme.selectionColor());
        renderHandle(to, page, xOffset, theme.selectionColor());
    }

    void copyToClipboard() {
        snapshot().ifPresent(selection -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(selection.text());
            Clipboard.getSystemClipboard().setContent(content);
        });
    }

    private void renderHandle(long offset, PageLayout page, double xOffset, String color) {
        handlePoint(offset, page, xOffset).ifPresent(point -> {
            var gc = renderer.getGraphicsContext();
            gc.setFill(Color.web(color, 0.92));
            gc.fillOval(point.x() - HANDLE_RADIUS, point.y() - HANDLE_RADIUS,
                    HANDLE_RADIUS * 2, HANDLE_RADIUS * 2);
        });
    }

    private Optional<HandlePoint> handlePoint(long offset, PageLayout page, double xOffset) {
        if (page == null || page.getLines().isEmpty()) return Optional.empty();
        for (LineLayout line : page.getLines()) {
            long lineStart = line.textOffset();
            long lineEnd = lineStart + Math.max(1, line.charLength());
            if (offset < lineStart || offset > lineEnd) continue;
            double span = Math.max(1, lineEnd - lineStart);
            double ratio = Math.max(0.0, Math.min(1.0, (offset - lineStart) / span));
            return Optional.of(new HandlePoint(
                    xOffset + line.x() + line.width() * ratio,
                    line.y() + line.height()));
        }
        return Optional.empty();
    }

    private static double distance(double x, double y, HandlePoint point) {
        if (point == null) return Double.POSITIVE_INFINITY;
        return Math.hypot(x - point.x(), y - point.y());
    }

    private long hitTestOffset(double x, double y, PageLayout page, double xOffset) {
        if (!engine.isOpen()) return 0;
        if (page == null || page.getLines().isEmpty()) return engine.getCurrentPosition().textOffset();
        double localX = x - xOffset;
        LineLayout nearest = page.getLines().getFirst();
        double best = Double.MAX_VALUE;
        for (LineLayout line : page.getLines()) {
            double center = line.y() + line.height() / 2.0;
            double distance = Math.abs(y - center);
            if (distance < best) {
                best = distance;
                nearest = line;
            }
            if (y >= line.y() && y <= line.y() + line.height()) {
                nearest = line;
                break;
            }
        }
        int length = Math.max(1, nearest.charLength());
        double ratio = nearest.width() <= 1 ? 0 : (localX - nearest.x()) / nearest.width();
        ratio = Math.max(0, Math.min(1, ratio));
        return nearest.textOffset() + Math.min(length, Math.max(0, (int) Math.round(ratio * length)));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private record HandlePoint(double x, double y) { }
}
