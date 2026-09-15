package com.myhomelibcorp.reader.core;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.core.cache.ImageCache;
import com.myhomelibcorp.reader.core.cache.PageCache;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.position.ReaderPositionManager;
import com.myhomelibcorp.reader.core.resource.SimpleResourceRepository;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import com.myhomelibcorp.reader.layout.FontMetricsProviderImpl;
import com.myhomelibcorp.reader.layout.TextLayoutEngine;
import com.myhomelibcorp.reader.render.api.ReaderRenderer;
import com.myhomelibcorp.reader.render.api.RenderMetrics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderPositionReflowAcceptanceTest {

    @Test
    void savedSemanticOffsetSurvivesReopenAndReflowSettings() throws Exception {
        ReaderDocument document = document();
        ReaderPosition saved = new ReaderPosition(1, 8_500L, 23, 7);
        ReaderEngine engine = engine(document, ReaderSettings.defaultSettings());

        ReaderEngine.PreparedBook prepared = engine.prepare(new MemorySource());
        engine.openPrepared(prepared, ReaderPosition.parse(saved.serialize()));
        assertThat(engine.getCurrentPosition().textOffset()).isEqualTo(saved.textOffset());
        assertThat(engine.getCurrentPosition().chapterIndex()).isEqualTo(document.chapterIndexAt(saved.textOffset()));

        ReaderSettings changed = ReaderSettings.defaultSettings()
                .withFontSize(24.0)
                .withTheme("dark");
        engine.applySettings(changed);
        engine.getCurrentPage(new PageDimensions(360, 540, 24, 24, 18, 18));
        assertThat(engine.getCurrentPosition().textOffset()).isEqualTo(saved.textOffset());

        engine.getCurrentPage(new PageDimensions(820, 620, 40, 40, 28, 28));
        assertThat(engine.getCurrentPosition().textOffset()).isEqualTo(saved.textOffset());
    }

    private static ReaderDocument document() {
        TextStorageImpl text = new TextStorageImpl();
        for (int i = 0; i < 180; i++) {
            text.startParagraph(TextStyle.NORMAL);
            text.append(("Абзац " + i + " — семантична позиція має пережити reflow. ").repeat(4), TextStyle.NORMAL);
        }
        long split = Math.max(1L, text.length() / 2L);
        return CompactReaderDocument.builder()
                .metadata(new BookMetadata("position", "Position", List.of("Author"), "uk", null, null,
                        List.of(), "", "", "", null, text.length()))
                .chapters(List.of(
                        new ChapterIndex("c1", "Chapter 1", 0, split, text.getParagraphCount() / 2),
                        new ChapterIndex("c2", "Chapter 2", split, text.length(), text.getParagraphCount())))
                .resources(new SimpleResourceRepository())
                .text(text)
                .toc(new DefaultTableOfContents())
                .totalTextLength(text.length())
                .build();
    }

    private static ReaderEngine engine(ReaderDocument document, ReaderSettings settings) {
        BookParser parser = new BookParser() {
            @Override public BookDocumentMetadata readMetadata(BookSource source) {
                return new BookDocumentMetadataSnapshot(document.metadata(), document.totalTextLength(), false,
                        document.chapters().size());
            }
            @Override public ReaderDocument parse(BookSource source, ParseOptions options) { return document; }
        };
        BookFormat format = new BookFormat() {
            @Override public String id() { return "position-test"; }
            @Override public Set<String> extensions() { return Set.of("test"); }
            @Override public boolean supports(BookSource source) { return true; }
            @Override public BookParser createParser() { return parser; }
        };
        BookFormatRegistry registry = new BookFormatRegistry() {
            @Override public Optional<BookFormat> findFormat(BookSource source) { return Optional.of(format); }
            @Override public Optional<BookFormat> findByExtension(String extension) { return Optional.of(format); }
            @Override public Optional<BookFormat> findById(String id) { return Optional.of(format); }
            @Override public List<BookFormat> getAllFormats() { return List.of(format); }
            @Override public void register(BookFormat ignored) { }
        };
        TextLayoutEngine layout = new TextLayoutEngine(new FontMetricsProviderImpl(settings), settings);
        return new ReaderEngine(registry, layout, new NoopRenderer(), new PageCache(8),
                new ImageCache(2 * 1024 * 1024), new ReaderPositionManager(), settings);
    }

    private static final class MemorySource implements BookSource {
        @Override public InputStream openStream() { return new ByteArrayInputStream(new byte[]{1}); }
        @Override public OptionalLong size() { return OptionalLong.of(1); }
        @Override public String name() { return "position.test"; }
        @Override public String extension() { return "test"; }
        @Override public String id() { return "position"; }
    }

    private static final class NoopRenderer implements ReaderRenderer {
        @Override public void renderPage(com.myhomelibcorp.reader.model.PageLayout page, ReaderTheme theme) { }
        @Override public RenderMetrics getMetrics() { return RenderMetrics.empty(); }
        @Override public void clear() { }
    }
}
