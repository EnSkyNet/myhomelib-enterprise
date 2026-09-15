package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/** Stateless painting helpers for transient Reader overlays. */
final class ReaderOverlayPainter {
    private ReaderOverlayPainter() { }

    static void renderAnnotation(GraphicsContext gc, ReaderAnnotationOverlay overlay, PageLayout page, double xOffset) {
        if (gc == null || overlay == null || page == null || page.isEmpty()) return;
        long from = overlay.startOffset();
        long to = overlay.endOffset();
        gc.setFill(Color.web(overlay.color(), overlay.note() ? 0.22 : 0.30));
        boolean painted = false;
        for (LineLayout line : page.getLines()) {
            long lineStart = line.textOffset();
            long lineEnd = lineStart + Math.max(1, line.charLength());
            long a = Math.max(from, lineStart);
            long b = Math.min(to, lineEnd);
            if (b <= a) continue;
            double span = Math.max(1, lineEnd - lineStart);
            double x1 = xOffset + line.x() + line.width() * ((a - lineStart) / span);
            double x2 = xOffset + line.x() + line.width() * ((b - lineStart) / span);
            gc.fillRoundRect(x1, line.y(), Math.max(2, x2 - x1), Math.max(2, line.height()), 3, 3);
            painted = true;
        }
        if (overlay.note()) renderNoteMarker(gc, overlay, page, xOffset, painted);
    }

    private static void renderNoteMarker(GraphicsContext gc, ReaderAnnotationOverlay overlay, PageLayout page,
                                         double xOffset, boolean rangePainted) {
        long offset = overlay.startOffset();
        for (LineLayout line : page.getLines()) {
            long lineStart = line.textOffset();
            long lineEnd = lineStart + Math.max(1, line.charLength());
            if (offset < lineStart || offset > lineEnd) continue;
            double span = Math.max(1, lineEnd - lineStart);
            double ratio = Math.max(0.0, Math.min(1.0, (offset - lineStart) / span));
            double x = xOffset + line.x() + line.width() * ratio;
            double y = line.y() + 2;
            double diameter = rangePainted ? 10 : 12;
            gc.setFill(Color.web(overlay.color(), 0.88));
            gc.fillOval(x - diameter / 2, y - diameter / 2, diameter, diameter);
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font(Math.max(7, diameter - 3)));
            gc.fillText("N", x - diameter * 0.27, y + diameter * 0.28);
            return;
        }
    }

    static void renderRange(GraphicsContext gc, long from, long to, PageLayout page, double xOffset,
                            String color, double opacity) {
        if (gc == null || page == null || page.isEmpty()) return;
        gc.setFill(Color.web(color, opacity));
        for (LineLayout line : page.getLines()) {
            long lineStart = line.textOffset();
            long lineEnd = lineStart + Math.max(1, line.charLength());
            long a = Math.max(from, lineStart);
            long b = Math.min(to, lineEnd);
            if (b <= a) continue;
            double span = Math.max(1, lineEnd - lineStart);
            double x1 = xOffset + line.x() + line.width() * ((a - lineStart) / span);
            double x2 = xOffset + line.x() + line.width() * ((b - lineStart) / span);
            gc.fillRoundRect(x1, line.y(), Math.max(2, x2 - x1), Math.max(2, line.height()), 3, 3);
        }
    }
}
