package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Pure geometry helper for annotation activation on the rendered page/spread. */
final class ReaderAnnotationHitTest {
    private static final double RANGE_PAD_X = 4.0;
    private static final double RANGE_PAD_Y = 3.0;
    private static final double MARKER_RADIUS = 9.0;

    private ReaderAnnotationHitTest() { }

    static Optional<ReaderAnnotationOverlay> hit(
            List<ReaderAnnotationOverlay> overlays,
            PageLayout left,
            PageLayout right,
            double rightOffset,
            boolean twoPage,
            double x,
            double y
    ) {
        if (overlays == null || overlays.isEmpty()) return Optional.empty();
        List<Candidate> candidates = new ArrayList<>();
        for (ReaderAnnotationOverlay overlay : overlays) {
            if (overlay == null) continue;
            collect(candidates, overlay, left, 0.0, x, y);
            if (twoPage && right != null && !right.isEmpty()) {
                collect(candidates, overlay, right, rightOffset, x, y);
            }
        }
        return candidates.stream()
                .sorted(Candidate.ORDER)
                .map(Candidate::overlay)
                .findFirst();
    }

    static List<ReaderAnnotationOverlay> visibleOrdered(
            List<ReaderAnnotationOverlay> overlays,
            PageLayout left,
            PageLayout right,
            boolean twoPage
    ) {
        if (overlays == null || overlays.isEmpty()) return List.of();
        long min = left == null || left.isEmpty() ? Long.MAX_VALUE : left.getStartOffset();
        long max = left == null || left.isEmpty() ? Long.MIN_VALUE : left.getEndOffset();
        if (twoPage && right != null && !right.isEmpty()) {
            min = Math.min(min, right.getStartOffset());
            max = Math.max(max, right.getEndOffset());
        }
        if (min == Long.MAX_VALUE || max == Long.MIN_VALUE) return List.of();
        long lower = min;
        long upper = max;
        return overlays.stream()
                .filter(item -> item != null && item.endOffset() >= lower && item.startOffset() <= upper)
                .sorted(Comparator
                        .comparingLong(ReaderAnnotationOverlay::startOffset)
                        .thenComparingLong(ReaderAnnotationOverlay::rangeLength)
                        .thenComparing(ReaderAnnotationOverlay::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ReaderAnnotationOverlay::id))
                .toList();
    }

    private static void collect(
            List<Candidate> candidates,
            ReaderAnnotationOverlay overlay,
            PageLayout page,
            double xOffset,
            double x,
            double y
    ) {
        if (page == null || page.isEmpty()) return;
        for (LineLayout line : page.getLines()) {
            long lineStart = line.textOffset();
            long lineEnd = lineStart + Math.max(1, line.charLength());

            if (overlay.note() && overlay.startOffset() >= lineStart && overlay.startOffset() <= lineEnd) {
                double span = Math.max(1, lineEnd - lineStart);
                double ratio = Math.max(0.0, Math.min(1.0, (overlay.startOffset() - lineStart) / span));
                double markerX = xOffset + line.x() + line.width() * ratio;
                double markerY = line.y() + 2.0;
                if (distanceSquared(x, y, markerX, markerY) <= MARKER_RADIUS * MARKER_RADIUS) {
                    candidates.add(new Candidate(overlay, true));
                    continue;
                }
            }

            long a = Math.max(overlay.startOffset(), lineStart);
            long b = Math.min(overlay.endOffset(), lineEnd);
            if (b <= a) continue;
            double span = Math.max(1, lineEnd - lineStart);
            double x1 = xOffset + line.x() + line.width() * ((a - lineStart) / span);
            double x2 = xOffset + line.x() + line.width() * ((b - lineStart) / span);
            if (x >= Math.min(x1, x2) - RANGE_PAD_X
                    && x <= Math.max(x1, x2) + RANGE_PAD_X
                    && y >= line.y() - RANGE_PAD_Y
                    && y <= line.y() + line.height() + RANGE_PAD_Y) {
                candidates.add(new Candidate(overlay, false));
            }
        }
    }

    private static double distanceSquared(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private record Candidate(ReaderAnnotationOverlay overlay, boolean marker) {
        private static final Comparator<Candidate> ORDER = Comparator
                .comparing(Candidate::marker).reversed()
                .thenComparingLong(candidate -> candidate.overlay().rangeLength())
                .thenComparing((Candidate candidate) -> safe(candidate.overlay().updatedAt()), Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.overlay().id(), Comparator.reverseOrder());

        private static Instant safe(Instant value) {
            return value == null ? Instant.EPOCH : value;
        }
    }
}
