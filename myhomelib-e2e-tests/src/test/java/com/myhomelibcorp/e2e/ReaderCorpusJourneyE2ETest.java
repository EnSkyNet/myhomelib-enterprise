package com.myhomelibcorp.e2e;

import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.annotation.AnnotationAnchor;
import com.myhomelibcorp.domain.model.annotation.AnnotationType;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteAnnotationRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBookmarkRepository;
import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.core.position.ReaderPositionManager;
import com.myhomelibcorp.reader.format.epub.EpubParser;
import com.myhomelibcorp.reader.format.fb2.Fb2StreamingParser;
import com.myhomelibcorp.reader.render.comic.ComicDocumentSession;
import com.myhomelibcorp.reader.render.comic.ComicPageSource;
import com.myhomelibcorp.reader.render.pdf.PdfDocumentSession;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Corpus-level Reader regression: supported formats, malformed/big/unicode cases and persisted reader state. */
class ReaderCorpusJourneyE2ETest {
    @TempDir Path tempDir;

    @Test
    void criticalFb2EpubPdfAndCbzCorpusOpensWithUnicodeWithoutUiThreadWork() {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            Path fb2 = writeFb2("unicode-book.fb2", "Київ — 日本語 — café", "Абзац із emoji 📚 та кирилицею.");
            ReaderDocument fb2Document = new Fb2StreamingParser().parse(new FileBookSource(fb2, "fb2-corpus"), ParseOptions.defaultOptions());
            try {
                assertThat(fb2Document.metadata().title()).isEqualTo("Київ — 日本語 — café");
                assertThat(fb2Document.text().getFullText()).contains("emoji 📚");
            } finally {
                closeResources(fb2Document);
            }

            Path epub = writeEpub("unicode.epub", "EPUB — Україна 日本", "Текст EPUB: Привіт, світ! こんにちは。");
            ReaderDocument epubDocument = new EpubParser().parse(new FileBookSource(epub, "epub-corpus"), ParseOptions.defaultOptions());
            try {
                assertThat(epubDocument.metadata().title()).isEqualTo("EPUB — Україна 日本");
                assertThat(epubDocument.text().getFullText()).contains("Привіт, світ!").contains("こんにちは");
            } finally {
                closeResources(epubDocument);
            }

            Path pdf = writePdf("sample.pdf", 3);
            try (PdfDocumentSession session = PdfDocumentSession.open(new FileBookSource(pdf, "pdf-corpus"))) {
                assertThat(session.pageCount()).isEqualTo(3);
                assertThat(session.pageSize(0).widthPoints()).isPositive();
            }

            Path cbz = writeCbz("comic.cbz", List.of("10-кінець.png", "2-середина.png", "1-початок.png"));
            try (ComicDocumentSession session = ComicDocumentSession.open(zipComicSource(cbz))) {
                assertThat(session.pageCount()).isEqualTo(3);
                assertThat(session.pageName(0)).isEqualTo("1-початок.png");
                assertThat(session.pageName(2)).isEqualTo("10-кінець.png");
                assertThat(session.render(0, 800, 900).width()).isPositive();
            }
        });
    }

    @Test
    void malformedCorpusFailsBoundedlyInsteadOfHangingOrLeakingRuntimeFailures() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            Path fb2 = tempDir.resolve("broken.fb2");
            Files.writeString(fb2, "<FictionBook><body><section>", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> new Fb2StreamingParser().parse(new FileBookSource(fb2), ParseOptions.defaultOptions()))
                    .isInstanceOf(IOException.class);

            Path epub = tempDir.resolve("broken.epub");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
                put(zip, "not-container.txt", "broken");
            }
            assertThatThrownBy(() -> new EpubParser().parse(new FileBookSource(epub), ParseOptions.defaultOptions()))
                    .isInstanceOf(IOException.class);

            Path pdf = tempDir.resolve("broken.pdf");
            Files.writeString(pdf, "not a pdf", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> PdfDocumentSession.open(new FileBookSource(pdf)))
                    .isInstanceOf(IOException.class);

            Path cbz = tempDir.resolve("broken.cbz");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
                put(zip, "readme.txt", "no image pages");
            }
            assertThatThrownBy(() -> ComicDocumentSession.open(zipComicSource(cbz)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("no supported image pages");
        });
    }

    @Test
    void multiMegabyteFb2CorpusStaysBoundedAndSearchable() {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            String large = ("Великий unicode абзац Київ 日本 café — стабільний regression corpus. ").repeat(45_000);
            Path fb2 = writeFb2("large.fb2", "Large corpus", large);
            ReaderDocument document = new Fb2StreamingParser().parse(new FileBookSource(fb2, "large-corpus"), ParseOptions.defaultOptions());
            try {
                assertThat(document.totalTextLength()).isGreaterThan(2_000_000L);
                assertThat(document.text().getText((int) document.totalTextLength() - 400, (int) document.totalTextLength()))
                        .contains("regression corpus");
            } finally {
                closeResources(document);
            }
        });
    }

    @Test
    void progressBookmarksAndAnnotationsSurviveIndependentRepositoryReopen() throws Exception {
        Path progressFile = tempDir.resolve("reader-position.properties");
        ReaderPosition expectedPosition = new ReaderPosition(2, 1_234, 17, 3);
        new ReaderPositionManager(new FilePositionProvider(progressFile)).savePosition("book-1", expectedPosition);
        ReaderPosition restored = new ReaderPositionManager(new FilePositionProvider(progressFile))
                .loadPosition("book-1").orElseThrow();
        assertThat(restored).isEqualTo(expectedPosition);

        Path db = tempDir.resolve("reader-state.db");
        DataSource firstDs = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        Flyway.configure().dataSource(firstDs).locations("classpath:db/migration").load().migrate();
        RepositoryFixture first = repositories(firstDs);
        first.jdbc.update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('book-1','Book','book.fb2',0,1)");
        first.jdbc.update("""
                INSERT INTO book_artifacts(artifact_id,book_id,artifact_name,file_name,local,remote,state)
                VALUES('artifact-1','book-1','book.fb2','book.fb2',1,0,'AVAILABLE')
                """);

        Bookmark bookmark = Bookmark.builder()
                .id("bookmark-1").bookId("book-1").paragraphId("p-2").charOffset(42).position(0.42)
                .chapterTitle("Розділ").context("Контекст закладки").createdAt(LocalDateTime.of(2026, 9, 12, 10, 0))
                .build();
        first.bookmarks.save(bookmark);

        Instant now = Instant.parse("2026-09-12T10:00:00Z");
        Annotation annotation = new Annotation("annotation-1", AnnotationType.HIGHLIGHT,
                new AnnotationAnchor("book-1", "artifact-1", "ch-1", "Розділ", "p-2",
                        40, 52, 0.42, "цитата", "до ", " після"),
                "#FFF59D", "нотатка", Set.of("regression", "reader"), now, now.plusSeconds(1));
        first.annotations.save(annotation);

        // New repository objects simulate application restart/reopen against the same SQLite file.
        DataSource reopenedDs = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        RepositoryFixture reopened = repositories(reopenedDs);
        assertThat(reopened.bookmarks.findByBookId("book-1")).containsExactly(bookmark);
        assertThat(reopened.annotations.findById("annotation-1")).contains(annotation);
        assertThat(reopened.annotations.findByBookId("book-1")).containsExactly(annotation);
    }

    private Path writeFb2(String name, String title, String body) throws IOException {
        Path file = tempDir.resolve(name);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info><genre>sf</genre><author><first-name>Тест</first-name><last-name>Автор</last-name></author>
                  <book-title>%s</book-title><lang>uk</lang></title-info></description>
                  <body><section><title><p>Розділ 1</p></title><p>%s</p></section></body>
                </FictionBook>
                """.formatted(escapeXml(title), escapeXml(body));
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeEpub(String name, String title, String body) throws IOException {
        Path file = tempDir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>");
            put(zip, "OPS/book.opf", """
                    <package><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>%s</dc:title><dc:creator>Автор</dc:creator><dc:language>uk</dc:language></metadata>
                    <manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                    <spine><itemref idref="c"/></spine></package>
                    """.formatted(escapeXml(title)));
            put(zip, "OPS/chapter.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Розділ</h1><p>"
                    + escapeXml(body) + "</p></body></html>");
        }
        return file;
    }

    private Path writePdf(String name, int pages) throws IOException {
        Path file = tempDir.resolve(name);
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < pages; i++) pdf.addPage(new PDPage(PDRectangle.A4));
            pdf.save(file.toFile());
        }
        return file;
    }

    private Path writeCbz(String name, List<String> pages) throws IOException {
        Path file = tempDir.resolve(name);
        byte[] png = png();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String page : pages) {
                zip.putNextEntry(new ZipEntry(page));
                zip.write(png);
                zip.closeEntry();
            }
        }
        return file;
    }

    private static ComicPageSource zipComicSource(Path file) {
        return new ComicPageSource() {
            @Override public List<String> listPageEntries() throws IOException {
                try (ZipFile zip = new ZipFile(file.toFile())) {
                    List<String> result = new ArrayList<>();
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (!entry.isDirectory()) result.add(entry.getName());
                    }
                    return result;
                }
            }
            @Override public InputStream openPage(String entryName) throws IOException {
                try (ZipFile zip = new ZipFile(file.toFile())) {
                    ZipEntry entry = zip.getEntry(entryName);
                    if (entry == null) throw new IOException("Missing CBZ entry: " + entryName);
                    try (InputStream in = zip.getInputStream(entry)) {
                        return new ByteArrayInputStream(in.readAllBytes());
                    }
                }
            }
        };
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(48, 64, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void closeResources(ReaderDocument document) {
        if (document != null && document.resources() instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
    }

    private static RepositoryFixture repositories(DataSource dataSource) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CollectionManager manager = new CollectionManager(jdbc, new DataSourceConfig());
        setAtomic(manager, "currentJdbcTemplate", jdbc);
        setAtomic(manager, "currentDataSource", dataSource);
        QueryExecutor query = new QueryExecutor(manager);
        return new RepositoryFixture(jdbc,
                new SqliteBookmarkRepository(manager, query),
                new SqliteAnnotationRepository(query, manager));
    }

    @SuppressWarnings("unchecked")
    private static void setAtomic(CollectionManager manager, String fieldName, Object value) throws Exception {
        Field field = CollectionManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        AtomicReference<Object> reference = (AtomicReference<Object>) field.get(manager);
        reference.set(value);
    }

    private record RepositoryFixture(JdbcTemplate jdbc, SqliteBookmarkRepository bookmarks,
                                     SqliteAnnotationRepository annotations) { }

    private static final class FilePositionProvider implements ReaderPositionManager.PositionProvider {
        private final Path file;
        private FilePositionProvider(Path file) { this.file = file; }

        @Override public Optional<ReaderPosition> load(String documentId) {
            Properties p = read();
            String value = p.getProperty(documentId);
            if (value == null || value.isBlank()) return Optional.empty();
            String[] parts = value.split(",", -1);
            if (parts.length != 4) return Optional.empty();
            return Optional.of(new ReaderPosition(Integer.parseInt(parts[0]), Long.parseLong(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
        }

        @Override public void save(String documentId, ReaderPosition position) {
            Properties p = read();
            p.setProperty(documentId, position.chapterIndex() + "," + position.textOffset() + ","
                    + position.paragraphIndex() + "," + position.charOffset());
            try (var out = output()) { p.store(out, "reader regression position"); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }

        private Properties read() {
            Properties p = new Properties();
            if (!Files.exists(file)) return p;
            try (var in = Files.newInputStream(file)) { p.load(in); }
            catch (IOException e) { throw new IllegalStateException(e); }
            return p;
        }

        private java.io.OutputStream output() throws IOException {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            return Files.newOutputStream(file);
        }
    }
}
