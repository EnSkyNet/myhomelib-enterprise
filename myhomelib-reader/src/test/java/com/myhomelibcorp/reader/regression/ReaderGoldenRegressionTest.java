package com.myhomelibcorp.reader.regression;

import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.api.TocEntry;
import com.myhomelibcorp.reader.core.position.ReaderPositionManager;
import com.myhomelibcorp.reader.format.fb2.Fb2StreamingParser;
import com.myhomelibcorp.reader.format.zip.ZipParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stable Reader golden-fixture coverage. These files are synthetic and intentionally exercise the
 * failure-prone FB2/ZIP structures seen in release acceptance without depending on a developer IDE
 * or a mutable external library.
 */
class ReaderGoldenRegressionTest {

    @Test
    void richFb2KeepsMetadataTocImagesSemanticStylesFootnotesAndReadingPosition() throws Exception {
        ReaderDocument document = new Fb2StreamingParser().parse(
                resource("golden/reader-rich.fb2", "reader-golden-rich"),
                ParseOptions.defaultOptions());
        try {
            assertThat(document.metadata().title()).isEqualTo("Reader Golden — Rich FB2");
            assertThat(document.metadata().authors())
                    .containsExactly("Дмитрий Дорничев", "Анна Тестова");
            assertThat(document.metadata().language()).isEqualTo("uk");
            assertThat(document.metadata().series()).isEqualTo("Golden Cycle");
            assertThat(document.metadata().sequenceNumber()).isEqualTo(7);
            assertThat(document.metadata().genres()).contains("sf", "adventure");

            assertThat(document.text().getFullText())
                    .contains("Розділ перший")
                    .contains("Епіграф, який має зберегти семантичний стиль")
                    .contains("Перший рядок вірша")
                    .contains("Текст підрозділу для перевірки вкладеного TOC")
                    .contains("Другий розділ завершує стабільний golden-документ")
                    .contains("Текст примітки для regression test");

            assertThat(document.text().getParagraphs())
                    .extracting(paragraph -> paragraph.style())
                    .contains(TextStyle.CHAPTER_TITLE, TextStyle.SECTION_TITLE,
                            TextStyle.EPIGRAPH, TextStyle.VERSE, TextStyle.POEM_AUTHOR,
                            TextStyle.FOOTNOTE);
            assertThat(document.text().getSpans(0, document.text().length()))
                    .extracting(span -> span.style())
                    .contains(TextStyle.STRONG, TextStyle.EMPHASIS);

            assertThat(document.resources().exists("cover.png")).isTrue();
            assertThat(document.resources().exists("illustration.png")).isTrue();
            try (InputStream cover = document.resources().open("cover.png").orElseThrow()) {
                assertThat(cover.readAllBytes()).isNotEmpty();
            }

            List<String> tocTitles = flattenTitles(document.toc().entries());
            assertThat(tocTitles).contains("Розділ перший", "Підрозділ один", "Розділ другий");
            assertThat(document.chapters()).isNotEmpty();
            assertThat(document.chapters())
                    .extracting(chapter -> chapter.title())
                    .contains("Розділ перший", "Розділ другий");

            long stableOffset = document.text().getFullText().indexOf("Другий розділ");
            assertThat(stableOffset).isGreaterThan(0);
            ReaderPosition expected = new ReaderPosition(
                    document.chapterIndexAt(stableOffset), stableOffset, 13, 2);
            ReaderPositionManager positions = new ReaderPositionManager();
            positions.savePosition("reader-golden-rich", expected);
            ReaderPosition restored = positions.validatePosition(document,
                    positions.loadPosition("reader-golden-rich").orElseThrow());
            assertThat(restored).isEqualTo(expected);
        } finally {
            closeResources(document);
        }
    }

    @Test
    void zipGoldenMergesBothFb2BooksAndBuildsStableBookToChapterToc() throws Exception {
        ReaderDocument document = new ZipParser().parse(
                resource("golden/reader-rich.zip", "reader-golden-zip"),
                ParseOptions.defaultOptions());
        try {
            assertThat(document.text().getFullText())
                    .contains("Другий розділ завершує стабільний golden-документ")
                    .contains("Контрольний текст другої книги всередині ZIP");

            assertThat(document.toc().entries()).hasSize(2);
            assertThat(document.toc().entries())
                    .extracting(TocEntry::title)
                    .containsExactly("Reader Golden — Rich FB2", "Reader Golden — Second FB2");
            assertThat(flattenTitles(document.toc().entries()))
                    .contains("Розділ перший", "Підрозділ один", "Другий ZIP-розділ");
            assertThat(document.toc().entries().get(1).textOffset())
                    .isGreaterThan(document.toc().entries().get(0).textOffset());
        } finally {
            closeResources(document);
        }
    }

    private static List<String> flattenTitles(List<TocEntry> roots) {
        List<String> result = new ArrayList<>();
        for (TocEntry entry : roots) {
            result.add(entry.title());
            if (entry.children() != null) result.addAll(flattenTitles(entry.children()));
        }
        return result;
    }

    private static BookSource resource(String name, String id) {
        URL url = ReaderGoldenRegressionTest.class.getClassLoader().getResource(name);
        if (url == null) throw new IllegalStateException("Missing test resource: " + name);
        return new BookSource() {
            @Override public InputStream openStream() throws IOException { return url.openStream(); }
            @Override public OptionalLong size() {
                try {
                    if ("file".equalsIgnoreCase(url.getProtocol())) {
                        return OptionalLong.of(Files.size(Path.of(url.toURI())));
                    }
                } catch (Exception ignored) { }
                return OptionalLong.empty();
            }
            @Override public String name() { return Path.of(name).getFileName().toString(); }
            @Override public String extension() {
                String file = name();
                int dot = file.lastIndexOf('.');
                return dot >= 0 ? file.substring(dot + 1) : "";
            }
            @Override public String id() { return id; }
        };
    }

    private static void closeResources(ReaderDocument document) throws Exception {
        if (document != null && document.resources() instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
