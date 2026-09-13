package com.myhomelibcorp.reader.format.fb2;

import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.TocEntry;
import com.myhomelibcorp.shared.archive.ZipCharsetSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: -Dmyhomelib.test.fb2CorpusDir=/path/to/external/archives. */
@EnabledIfSystemProperty(named = "myhomelib.test.fb2CorpusDir", matches = ".+")
class RealFb2ReaderProbeTest {
    @Test
    void parsesEveryFb2ArchiveWithValidTextAndNavigationOffsets() throws Exception {
        Path directory = Path.of(System.getProperty("myhomelib.test.fb2CorpusDir"));
        int books = 0;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".zip")).sorted().toList()) {
                try (ZipFile archive = ZipCharsetSupport.open(path)) {
                    var entries = archive.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".fb2")) continue;
                        BookSource source = new BookSource() {
                            public InputStream openStream() throws IOException { return archive.getInputStream(entry); }
                            public OptionalLong size() { return OptionalLong.of(entry.getSize()); }
                            public String name() { return entry.getName(); }
                            public String extension() { return "fb2"; }
                            public String id() { return path + "!/" + entry.getName(); }
                        };
                        long started = System.nanoTime();
                        var document = new Fb2StreamingParser().parse(source, ParseOptions.defaultOptions());
                        try {
                            assertThat(document.metadata().title()).isNotBlank().isNotEqualTo("Без назви");
                            long length = document.totalTextLength();
                            assertThat(length).isPositive().isEqualTo(document.text().length());
                            assertThat(document.text().getParagraphCount()).isPositive();
                            assertThat(document.chapters()).isNotEmpty();
                            for (var chapter : document.chapters()) {
                                assertThat(chapter.startOffset()).isBetween(0L, length);
                                assertThat(chapter.endOffset()).isBetween(chapter.startOffset(), length);
                            }
                            checkToc(document.toc().entries(), length);
                            books++;
                            System.out.printf(Locale.ROOT,
                                    "FB2_PROBE book=%d bytes=%d textCharacters=%d paragraphs=%d chapters=%d toc=%d resources=%d elapsedMs=%.1f%n",
                                    books, entry.getSize(), length, document.text().getParagraphCount(),
                                    document.chapters().size(), document.toc().size(), document.resources().count(),
                                    (System.nanoTime() - started) / 1_000_000.0);
                        } finally {
                            if (document.resources() instanceof AutoCloseable resources) resources.close();
                        }
                    }
                }
            }
        }
        assertThat(books).as("FB2 entries in the external corpus").isPositive();
    }

    private static void checkToc(List<TocEntry> entries, long length) {
        for (TocEntry entry : entries) {
            assertThat(entry.textOffset()).isBetween(0L, length);
            if (entry.children() != null) checkToc(entry.children(), length);
        }
    }
}
