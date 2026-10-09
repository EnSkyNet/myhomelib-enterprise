package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Reader Book Map projection for the currently open text document.
 *
 * <p>This boundary owns chapter-state and per-chapter annotation/bookmark aggregation only. It
 * deliberately does not own JavaFX controls or navigation so the workspace controller remains the
 * lifecycle owner.</p>
 */
@Component
@RequiredArgsConstructor
public final class ReaderBookMapCoordinator {
    private final LocalizationService i18n;

    List<BookMapRow> buildRows(
            ReaderDocument document,
            ReaderPosition currentPosition,
            ReaderAnnotationPresentation annotations,
            List<ReaderPosition> bookmarkPositions) {
        if (document.chapters() == null) return List.of();

        long currentOffset = currentPosition == null ? 0L : currentPosition.textOffset();
        List<ReaderAnnotationOverlay> overlays = annotations.overlays();
        List<BookMapRow> rows = new ArrayList<>(document.chapters().size());

        for (int i = 0; i < document.chapters().size(); i++) {
            ChapterIndex chapter = document.chapters().get(i);
            int notes = 0;
            int highlights = 0;
            for (ReaderAnnotationOverlay annotation : overlays) {
                if (!chapter.containsOffset(annotation.startOffset())) continue;
                if (annotation.note()) notes++; else highlights++;
            }

            int chapterBookmarks = 0;
            for (ReaderPosition position : bookmarkPositions) {
                if (position != null && chapter.containsOffset(position.textOffset())) chapterBookmarks++;
            }

            boolean passed = currentOffset >= chapter.endOffset();
            boolean current = chapter.containsOffset(currentOffset);
            rows.add(new BookMapRow(
                    i,
                    chapter.startOffset(),
                    label(chapter, notes, highlights, chapterBookmarks, passed, current)));
        }
        return List.copyOf(rows);
    }

    private String label(
            ChapterIndex chapter,
            int notes,
            int highlights,
            int bookmarks,
            boolean passed,
            boolean current) {
        String state = current ? "▶ " : passed ? "✓ " : "○ ";
        String title = chapter.title() == null || chapter.title().isBlank()
                ? i18n.text("ui.reader.bookmap.untitled_chapter")
                : chapter.title();
        return state + title + "   📝 " + notes + "   ▰ " + highlights + "   🔖 " + bookmarks;
    }

    record BookMapRow(int chapterIndex, long startOffset, String label) {
        @Override public String toString() { return label; }
    }
}
