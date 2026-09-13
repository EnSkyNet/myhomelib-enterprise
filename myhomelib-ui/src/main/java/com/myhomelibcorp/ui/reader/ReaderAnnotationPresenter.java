package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.annotation.AnnotationAnchorData;
import com.myhomelibcorp.application.annotation.AnnotationReaderItem;
import com.myhomelibcorp.application.annotation.AnnotationReaderResolver;
import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderSelection;

import java.util.ArrayList;
import java.util.List;

/** Bridges renderer-neutral reader values and application annotation DTOs at the UI boundary. */
final class ReaderAnnotationPresenter {
    private ReaderAnnotationPresenter() { }

    static AnnotationAnchorData anchor(String bookId, String artifactId, ReaderSelection selection) {
        if (selection == null) throw new IllegalArgumentException("selection is required");
        return new AnnotationAnchorData(
                bookId,
                artifactId,
                selection.chapterId(),
                selection.chapterTitle(),
                selection.paragraphId(),
                selection.startOffset(),
                selection.endOffset(),
                selection.position(),
                selection.text(),
                selection.prefix(),
                selection.suffix());
    }

    /**
     * Resolves persisted anchors against the immutable parsed reader document. Exact offsets are
     * checked without materializing the whole book; full text is allocated lazily only if quote
     * relocation is actually needed.
     */
    static List<ReaderAnnotationOverlay> overlays(
            List<AnnotationReaderItem> annotations,
            String artifactId,
            ReaderDocument document) {
        if (annotations == null || annotations.isEmpty() || document == null || document.text() == null) {
            return List.of();
        }
        List<ReaderAnnotationOverlay> result = new ArrayList<>(annotations.size());
        String fullText = null;
        int textLength = document.text().length();
        for (AnnotationReaderItem annotation : annotations) {
            if (annotation == null || annotation.anchor() == null) continue;
            AnnotationAnchorData anchor = annotation.anchor();
            if (!anchor.allowsArtifact(artifactId)) continue;

            long start = anchor.startOffset();
            long end = anchor.endOffset();
            boolean exact = end <= textLength && start <= end;
            if (exact && !anchor.quote().isEmpty()) {
                String current = document.text().getText(Math.toIntExact(start), Math.toIntExact(end));
                exact = anchor.quote().equals(current);
            }
            if (!exact) {
                if (fullText == null) fullText = document.text().getFullText();
                var relocated = AnnotationReaderResolver.resolve(annotation, artifactId, fullText).orElse(null);
                if (relocated == null) continue;
                start = relocated.startOffset();
                end = relocated.endOffset();
            }
            result.add(new ReaderAnnotationOverlay(
                    annotation.id(), start, end, annotation.color(), annotation.note()));
        }
        return List.copyOf(result);
    }
}
