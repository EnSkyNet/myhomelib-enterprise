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
import com.myhomelibcorp.reader.render.api.RenderSurface;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderEngineNavigationTest {

    @Test
    void previousPageAfterArbitraryJumpMovesBackByARealPage() throws Exception {
        TextStorageImpl text = new TextStorageImpl();
        for (int i = 0; i < 120; i++) {
            text.startParagraph(TextStyle.NORMAL);
            text.append(("Абзац " + i + " з текстом для перевірки правильної зворотної пагінації. ").repeat(5),
                    TextStyle.NORMAL);
            text.endParagraph();
        }

        ReaderDocument document = CompactReaderDocument.builder()
                .metadata(new BookMetadata("nav", "Navigation", List.of("Author"), "uk", null, null,
                        List.of(), "", "", "", null, text.length()))
                .chapters(List.of(new ChapterIndex("c1", "Chapter", 0, text.length(), text.getParagraphCount())))
                .resources(new SimpleResourceRepository())
                .text(text)
                .toc(new DefaultTableOfContents())
                .totalTextLength(text.length())
                .build();

        BookFormatRegistry registry = registryFor(document);
        ReaderSettings settings = ReaderSettings.defaultSettings();
        TextLayoutEngine layout = new TextLayoutEngine(new FontMetricsProviderImpl(settings), settings);
        ReaderEngine engine = new ReaderEngine(
                registry,
                layout,
                new NoopRenderer(),
                new PageCache(8),
                new ImageCache(2 * 1024 * 1024),
                new ReaderPositionManager(),
                settings
        );

        engine.open(new MemorySource());
        PageDimensions dimensions = new PageDimensions(420, 320, 30, 30, 20, 20);
        long jump = Math.min(10_000, text.length() - 1L);
        engine.goToPosition(new ReaderPosition(0, jump, 0, 0));

        engine.previousPage(dimensions);
        long previous = engine.getCurrentPosition().textOffset();
        long previousEnd = engine.getCurrentPage(dimensions).getEndOffset();

        assertThat(previous).isLessThan(jump);
        assertThat(jump - previous).isGreaterThan(100);
        assertThat(previousEnd).isLessThanOrEqualTo(jump);
    }

    private BookFormatRegistry registryFor(ReaderDocument document) {
        BookParser parser = new BookParser() {
            @Override
            public BookDocumentMetadata readMetadata(BookSource source) {
                return new BookDocumentMetadataSnapshot(document.metadata(), document.totalTextLength(), false, 1);
            }

            @Override
            public ReaderDocument parse(BookSource source, ParseOptions options) {
                return document;
            }
        };

        BookFormat format = new BookFormat() {
            @Override public String id() { return "test"; }
            @Override public Set<String> extensions() { return Set.of("test"); }
            @Override public boolean supports(BookSource source) { return true; }
            @Override public BookParser createParser() { return parser; }
        };

        return new BookFormatRegistry() {
            @Override public Optional<BookFormat> findFormat(BookSource source) { return Optional.of(format); }
            @Override public Optional<BookFormat> findByExtension(String extension) { return Optional.of(format); }
            @Override public Optional<BookFormat> findById(String id) { return Optional.of(format); }
            @Override public List<BookFormat> getAllFormats() { return List.of(format); }
            @Override public void register(BookFormat ignored) { }
        };
    }

    private static final class MemorySource implements BookSource {
        @Override public InputStream openStream() { return new ByteArrayInputStream(new byte[]{1}); }
        @Override public OptionalLong size() { return OptionalLong.of(1); }
        @Override public String name() { return "test.test"; }
        @Override public String extension() { return "test"; }
        @Override public String id() { return "nav"; }
    }

    private static final class NoopRenderer implements ReaderRenderer {
        @Override public void renderPage(com.myhomelibcorp.reader.model.PageLayout page,
                                         RenderSurface surface, ReaderTheme theme) { }
        @Override public RenderMetrics getMetrics() { return RenderMetrics.empty(); }
        @Override public void clear() { }
    }
}
