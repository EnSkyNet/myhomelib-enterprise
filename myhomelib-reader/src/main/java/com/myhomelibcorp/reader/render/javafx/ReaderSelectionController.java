package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderTheme;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;

/**
 * Owns selection offsets, hit-testing, overlay rendering and clipboard copying.
 * Source text offsets are preserved; no visual selection state leaks into the
 * reader engine or page-navigation state.
 */
final class ReaderSelectionController {
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
            double length = Math.max(1, lineEnd - lineStart);
            double x1 = xOffset + line.x() + line.width() * ((a - lineStart) / length);
            double x2 = xOffset + line.x() + line.width() * ((b - lineStart) / length);
            gc.fillRect(x1, line.y(), Math.max(1, x2 - x1), Math.max(1, line.height()));
        }
    }

    void copyToClipboard() {
        if (!hasSelection() || engine.getCurrentDocument() == null) return;
        long from = Math.min(anchorOffset, focusOffset);
        long to = Math.max(anchorOffset, focusOffset);
        int length = engine.getCurrentDocument().text().length();
        int a = (int) Math.max(0, Math.min(length, from));
        int b = (int) Math.max(a, Math.min(length, to));
        if (b <= a) return;
        String selected = engine.getCurrentDocument().text().getText(a, b);
        if (selected == null || selected.isBlank()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(selected);
        Clipboard.getSystemClipboard().setContent(content);
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
}
