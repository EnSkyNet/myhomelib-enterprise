package com.myhomelibcorp.infrastructure.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.SeriesOmnibusPlanner;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class Fb2OmnibusMergerTest {
    private static final String FB = "http://www.gribuser.ru/xml/fictionbook/2.0";
    private static final String XLINK = "http://www.w3.org/1999/xlink";
    @TempDir Path temp;

    @Test
    void combinedFb2KeepsNestedChaptersNotesImagesAndUniqueAnchors() throws Exception {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book first = source(resources, "Том 1-2", "Перший текст", false);
        Book second = source(resources, "Том 3", "Другий текст", false);
        SeriesOmnibusPlanner.SeriesPlan plan = new SeriesOmnibusPlanner.SeriesPlan(
                "Письменник", "Цикл", List.of(first, second));
        Path output = temp.resolve("full-series.fb2");

        new SeriesOmnibusWriter(resources).assemble(
                plan, output, ExportRequest.ExportFormat.FB2, () -> false);

        Document doc = parse(Files.readAllBytes(output));
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("FictionBook");
        assertThat(doc.getElementsByTagNameNS(FB, "body").getLength()).isEqualTo(2);
        assertThat(doc.getElementsByTagNameNS(FB, "binary").getLength()).isEqualTo(2);
        assertThat(doc.getElementsByTagNameNS(FB, "emphasis").getLength()).isEqualTo(2);
        String xml = Files.readString(output);
        assertThat(xml).contains("2 книг, 3 томів", "Перший текст", "Другий текст",
                "Загальний зміст", "Том 1-2", "Том 3");
        assertThat(xml.indexOf("Перший текст")).isLessThan(xml.indexOf("Другий текст"));

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < doc.getElementsByTagName("*").getLength(); i++) {
            Element e = (Element) doc.getElementsByTagName("*").item(i);
            if (e.hasAttribute("id")) assertThat(ids.add(e.getAttribute("id"))).isTrue();
        }
        assertThat(ids).contains("mhl_volume_1", "mhl_volume_2", "mhl_contents");
        int internalLinks = 0;
        for (int i = 0; i < doc.getElementsByTagName("*").getLength(); i++) {
            Element element = (Element) doc.getElementsByTagName("*").item(i);
            String href = element.getAttributeNS(XLINK, "href");
            if (href.startsWith("#")) {
                assertThat(ids).contains(href.substring(1));
                internalLinks++;
            }
        }
        assertThat(internalLinks).isGreaterThan(8);
    }

    @Test
    void fb2ZipHasOneActualReadableFb2Member() throws Exception {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book first = source(resources, "Том 1", "Вміст", false);
        Path zipped = temp.resolve("complete.fb2.zip");
        new SeriesOmnibusWriter(resources).assemble(
                new SeriesOmnibusPlanner.SeriesPlan("Автор", "Серія", List.of(first)),
                zipped, ExportRequest.ExportFormat.FB2_ZIP, () -> false);

        try (ZipFile zip = new ZipFile(zipped.toFile(), StandardCharsets.UTF_8)) {
            assertThat(zip.size()).isEqualTo(1);
            var entry = zip.entries().nextElement();
            assertThat(entry.getName()).isEqualTo("complete.fb2");
            try (var input = zip.getInputStream(entry)) {
                assertThat(parse(input.readAllBytes()).getElementsByTagNameNS(FB, "section").getLength())
                        .isGreaterThanOrEqualTo(3);
            }
        }
    }

    @Test
    void maliciousDocumentWithDoctypeIsRejectedBeforePublication() throws Exception {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book malformed = source(resources, "Том 1", "text", true);
        Path target = temp.resolve("invalid.fb2");
        assertThatThrownBy(() -> new SeriesOmnibusWriter(resources).assemble(
                new SeriesOmnibusPlanner.SeriesPlan("Автор", "Серія", List.of(malformed)),
                target, ExportRequest.ExportFormat.FB2, () -> false))
                .isInstanceOf(java.io.IOException.class);
        assertThat(target).doesNotExist();
    }

    @Test
    void cancellationLeavesNoPartiallyPublishedFile() {
        BookResourcePort resources = mock(BookResourcePort.class);
        Book first = source(resources, "Том 1", "text", false);
        Path output = temp.resolve("cancel.fb2");
        assertThatThrownBy(() -> new SeriesOmnibusWriter(resources).assemble(
                new SeriesOmnibusPlanner.SeriesPlan("Автор", "Серія", List.of(first)),
                output, ExportRequest.ExportFormat.FB2, () -> true))
                .isInstanceOf(java.io.InterruptedIOException.class);
        assertThat(output).doesNotExist();
    }

    private static Book source(BookResourcePort resources, String title, String text, boolean dtd) {
        Book book = mock(Book.class);
        BookId id = BookId.generate();
        when(book.getId()).thenReturn(id);
        when(book.getTitle()).thenReturn(title);
        String original = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                   xmlns:l="http://www.w3.org/1999/xlink">
                <description><title-info><genre>sf</genre>
                  <author><first-name>Author</first-name></author>
                  <book-title>%s</book-title>
                  <coverpage><image l:href="#cover.jpg"/></coverpage><lang>uk</lang>
                </title-info></description>
                <body><title><p>Original</p></title>
                  <section id="chapter"><title><p>Розділ 1</p></title>
                    <p><emphasis>%s</emphasis><a l:href="#note1">[1]</a></p>
                    <image l:href="#cover.jpg"/>
                  </section>
                </body>
                <body name="notes"><section id="note1"><title><p>Примітка</p></title>
                  <p>Службова примітка</p></section></body>
                <binary id="cover.jpg" content-type="image/jpeg">AQID</binary>
                </FictionBook>
                """.formatted(title, text);
        if (dtd) original = original.replace("<FictionBook", "<!DOCTYPE FictionBook [<!ENTITY bad SYSTEM \"file:///etc/passwd\">]><FictionBook");
        byte[] sourceBytes = original.getBytes(StandardCharsets.UTF_8);
        when(resources.readBookData(book)).thenAnswer(ignored ->
                Optional.of(new ByteArrayInputStream(sourceBytes)));
        return book;
    }

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
}
