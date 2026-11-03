package com.myhomelibcorp.infrastructure.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.SeriesOmnibusPlanner;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeriesOmnibusWriterTest {
    @TempDir Path folder;

    @Test
    void readableTxtCombinesBooksInSeriesOrder() throws Exception {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book first = txt(resources, "First", "Текст першої книги");
        Book second = txt(resources, "Second", "Текст другої книги");
        Path output = folder.resolve("series.txt");

        new SeriesOmnibusWriter(resources).assemble(
                new SeriesOmnibusPlanner.SeriesPlan("Author", "Cycle", List.of(first, second)),
                output, ExportRequest.ExportFormat.TXT, () -> false);

        String content = Files.readString(output);
        assertThat(content).contains("Author — Cycle", "=== First ===",
                        "Текст першої книги", "=== Second ===", "Текст другої книги");
        assertThat(content.indexOf("Текст першої книги"))
                .isLessThan(content.indexOf("Текст другої книги"));
    }

    @Test
    void readableEpubHasStoredMimetypeAndOrderedNavigableBooks() throws Exception {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book first = txt(resources, "Том & один", "Перший <текст>");
        Book second = txt(resources, "Том два", "Другий текст");
        Path epub = folder.resolve("series.epub");

        new SeriesOmnibusWriter(resources).assemble(
                new SeriesOmnibusPlanner.SeriesPlan("Author", "Cycle", List.of(first, second)),
                epub, ExportRequest.ExportFormat.EPUB, () -> false);

        try (ZipFile archive = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            var mimetype = archive.getEntry("mimetype");
            assertThat(mimetype).isNotNull();
            assertThat(mimetype.getMethod()).isEqualTo(java.util.zip.ZipEntry.STORED);
            assertThat(read(archive, "mimetype")).isEqualTo("application/epub+zip");
            assertThat(read(archive, "META-INF/container.xml"))
                    .contains("OEBPS/content.opf");
            String packageXml = read(archive, "OEBPS/content.opf");
            assertThat(packageXml)
                    .contains("book-1.xhtml", "book-2.xhtml", "itemref idref=");
            assertThat(packageXml).contains("<spine><itemref idref=\"nav\"/>");
            String nav = read(archive, "OEBPS/nav.xhtml");
            assertThat(nav).contains("Том &amp; один", "book-1.xhtml", "book-2.xhtml",
                    "book-1.xhtml#chapter-1", "book-2.xhtml#chapter-1",
                    "Загальний зміст");
            assertThat(read(archive, "OEBPS/book-1.xhtml"))
                    .contains("id=\"chapter-1\"", "Перший &lt;текст&gt;");
            assertThat(read(archive, "OEBPS/book-2.xhtml")).contains("Другий текст");
        }
    }

    @Test
    void mergedFb2PreservesNativeBooksAndChapterHeadings() throws Exception {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book first = fb2(resources, "Перша & книга", "Перша глава", "Текст <першої>");
        Book second = fb2(resources, "Друга", "Друга глава", "Текст другої");
        Path output = folder.resolve("series.fb2");

        new SeriesOmnibusWriter(resources).assemble(
                new SeriesOmnibusPlanner.SeriesPlan("Автор & команда", "Цикл",
                        List.of(first, second)),
                output, ExportRequest.ExportFormat.FB2, () -> false);

        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        org.w3c.dom.Document doc;
        try (InputStream in = Files.newInputStream(output)) {
            doc = factory.newDocumentBuilder().parse(in);
        }
        String ns = "http://www.gribuser.ru/xml/fictionbook/2.0";
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("FictionBook");
        org.w3c.dom.NodeList sections = doc.getElementsByTagNameNS(ns, "section");
        assertThat(sections.getLength()).isGreaterThanOrEqualTo(5); // contents, volumes, chapters
        String content = doc.getDocumentElement().getTextContent();
        assertThat(content).contains("Перша & книга", "Текст <першої>",
                "Перша глава", "Друга глава", "Текст другої",
                "Загальний зміст");
        assertThat(content.indexOf("Текст <першої>")).isLessThan(content.indexOf("Текст другої"));
        assertThat(Files.readString(output)).contains("mhl_contents", "mhl_volume_1", "mhl_volume_2");
    }

    @Test
    void singleFb2ZipContainsOneReadableMergedBook() throws Exception {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book first = fb2(resources, "First", "Chapter", "Text");
        Path output = folder.resolve("series.fb2.zip");

        new SeriesOmnibusWriter(resources).assemble(
                new SeriesOmnibusPlanner.SeriesPlan("Author", "Series", List.of(first)),
                output, ExportRequest.ExportFormat.FB2_ZIP, () -> false);

        try (ZipFile archive = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertThat(archive.size()).isOne();
            var entry = archive.entries().nextElement();
            assertThat(entry.getName()).endsWith(".fb2");
            assertThat(read(archive, entry.getName())).contains("FictionBook", "Загальний зміст");
        }
    }

    @Test
    void cancellationDoesNotPublishTruncatedEbook() {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book first = txt(resources, "First", "Sample");
        Path output = folder.resolve("cancel.epub");
        assertThatThrownBy(() -> new SeriesOmnibusWriter(resources).assemble(
                new SeriesOmnibusPlanner.SeriesPlan("Author", "Cycle", List.of(first)),
                output, ExportRequest.ExportFormat.EPUB, () -> true))
                .isInstanceOf(InterruptedIOException.class);
        assertThat(output).doesNotExist();
    }

    @Test
    void rejectsOutputFormatsWhichWouldMisrepresentAReadableBook() {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book first = txt(resources, "First", "Sample");
        Path output = folder.resolve("not-an-epub.pdf");
        assertThatThrownBy(() -> new SeriesOmnibusWriter(resources).assemble(
                new SeriesOmnibusPlanner.SeriesPlan("Author", "Cycle", List.of(first)),
                output, ExportRequest.ExportFormat.PDF, () -> false))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("EPUB or TXT");
        assertThat(output).doesNotExist();
    }

    private static Book txt(BookResourcePort resources, String title, String body) {
        Book book = mock(Book.class);
        BookId id = BookId.generate();
        when(book.getId()).thenReturn(id);
        when(book.getTitle()).thenReturn(title);
        when(book.getFileName()).thenReturn(id.asString() + ".txt");
        byte[] payload = (title + "\n" + body).getBytes(StandardCharsets.UTF_8);
        when(resources.readBookData(book)).thenAnswer(call ->
                Optional.of(new ByteArrayInputStream(payload)));
        return book;
    }

    private static Book fb2(BookResourcePort resources, String title, String chapter, String body) {
        Book book = mock(Book.class);
        BookId id = BookId.generate();
        when(book.getId()).thenReturn(id);
        when(book.getTitle()).thenReturn(title);
        when(book.getFileName()).thenReturn(id.asString() + ".fb2");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info>
                    <genre>sf</genre>
                    <author><nickname>Author</nickname></author>
                    <book-title>Fixture</book-title><lang>uk</lang>
                  </title-info></description>
                  <body><section id="first"><title><p>%s</p></title>
                    <section id="chapter"><title><p>%s</p></title>
                      <p>%s</p>
                    </section>
                  </section></body>
                </FictionBook>
                """.formatted(escapeXml(title), escapeXml(chapter), escapeXml(body));
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        when(resources.readBookData(book)).thenAnswer(call ->
                Optional.of(new ByteArrayInputStream(bytes)));
        return book;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String read(ZipFile archive, String entry) throws Exception {
        try (InputStream in = archive.getInputStream(archive.getEntry(entry))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
