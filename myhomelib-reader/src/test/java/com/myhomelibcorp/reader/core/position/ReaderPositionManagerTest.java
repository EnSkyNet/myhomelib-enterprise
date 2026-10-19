package com.myhomelibcorp.reader.core.position;

import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderPositionManagerTest {
    private final ReaderPositionManager manager = new ReaderPositionManager();

    @Test
    void emptyDocumentNeverProducesNegativeChapterIndex() {
        var document = document(0, List.of());
        var persisted = new ReaderPosition(7, 100, -2, -3);

        assertThat(manager.validatePosition(document, persisted)).isEqualTo(ReaderPosition.start());
        assertThat(manager.endPosition(document)).isEqualTo(ReaderPosition.start());
    }

    @Test
    void documentWithoutChapterMetadataUsesSafeZeroChapter() {
        var document = document(12, List.of());

        assertThat(manager.validatePosition(document, new ReaderPosition(-4, 5, 1, 2)))
                .isEqualTo(new ReaderPosition(0, 5, 1, 2));
        assertThat(manager.endPosition(document))
                .isEqualTo(new ReaderPosition(0, 11, 0, 0));
    }

    @Test
    void invalidPersistedCoordinatesAreNormalizedWithoutMovingValidOffset() {
        var document = document(100, List.of(
                new ChapterIndex("c1", "One", 0, 50, 1),
                new ChapterIndex("c2", "Two", 50, 100, 1)
        ));

        ReaderPosition normalized = manager.validatePosition(
                document, new ReaderPosition(99, 70, -4, -8));

        assertThat(normalized).isEqualTo(new ReaderPosition(1, 70, 0, 0));
    }

    @Test
    void offsetPastEndClampsToLastCharacterAndLastChapter() {
        var document = document(100, List.of(
                new ChapterIndex("c1", "One", 0, 50, 1),
                new ChapterIndex("c2", "Two", 50, 100, 1)
        ));

        assertThat(manager.validatePosition(document, new ReaderPosition(0, 500, 4, 9)))
                .isEqualTo(new ReaderPosition(1, 99, 0, 0));
        assertThat(manager.endPosition(document))
                .isEqualTo(new ReaderPosition(1, 99, 0, 0));
    }

    @Test
    void evictsLeastRecentlyUsedPositionsButRetainsMostRecent() {
        ReaderPositionManager bounded = new ReaderPositionManager(null, 3);
        bounded.savePosition("a", ReaderPosition.start());
        bounded.savePosition("b", new ReaderPosition(0, 2, 0, 0));
        bounded.savePosition("c", new ReaderPosition(0, 3, 0, 0));
        assertThat(bounded.loadPosition("a")).isPresent(); // refresh access order
        bounded.savePosition("d", new ReaderPosition(0, 4, 0, 0));
        assertThat(bounded.getCurrentPosition("b")).isEmpty();
        assertThat(bounded.getCurrentPosition("a")).isPresent();
        assertThat(bounded.getCurrentPosition("c")).isPresent();
        assertThat(bounded.getCurrentPosition("d")).isPresent();
    }

    @Test
    void reloadsEvictedPositionsFromPersistentProvider() {
        java.util.Map<String, ReaderPosition> persistent = new java.util.HashMap<>();
        ReaderPositionManager.PositionProvider provider = new ReaderPositionManager.PositionProvider() {
            public java.util.Optional<ReaderPosition> load(String id) {
                return java.util.Optional.ofNullable(persistent.get(id));
            }
            public void save(String id, ReaderPosition pos) { persistent.put(id, pos); }
        };
        ReaderPositionManager bounded = new ReaderPositionManager(provider, 1);
        ReaderPosition saved = new ReaderPosition(0, 89, 1, 0);
        bounded.savePosition("book-a", saved);
        bounded.savePosition("book-b", ReaderPosition.start());
        assertThat(bounded.getCurrentPosition("book-a")).isEmpty();
        assertThat(bounded.loadPosition("book-a")).contains(saved);
    }

    private static CompactReaderDocument document(long textLength, List<ChapterIndex> chapters) {
        return CompactReaderDocument.builder()
                .chapters(chapters)
                .totalTextLength(textLength)
                .build();
    }
}
