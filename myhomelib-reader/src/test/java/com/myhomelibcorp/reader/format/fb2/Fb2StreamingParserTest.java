package com.myhomelibcorp.reader.format.fb2;

import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.core.resource.HybridResourceRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class Fb2StreamingParserTest {

    @Test
    void parsesTextParagraphsMetadataAndNestedToc() throws Exception {
        String fb2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                             xmlns:l="http://www.w3.org/1999/xlink">
                  <description>
                    <title-info>
                      <genre>sf</genre>
                      <author><first-name>Іван</first-name><last-name>Тестовий</last-name></author>
                      <book-title>Тестова книга</book-title>
                      <lang>uk</lang>
                    </title-info>
                  </description>
                  <body>
                    <section>
                      <title><p>Розділ один</p></title>
                      <p>Перший абзац із достатньою кількістю тексту для перевірки.</p>
                      <p>Другий абзац.</p>
                      <section>
                        <title><p>Підрозділ</p></title>
                        <p>Текст підрозділу.</p>
                      </section>
                    </section>
                  </body>
                </FictionBook>
                """;

        ReaderDocument document = new Fb2StreamingParser().parse(
                new MemoryBookSource(fb2), ParseOptions.withoutImages());

        assertThat(document.metadata().title()).isEqualTo("Тестова книга");
        assertThat(document.metadata().authors()).contains("Іван Тестовий");
        assertThat(document.text().getFullText()).contains("Перший абзац", "Другий абзац", "Текст підрозділу");
        assertThat(document.text().getParagraphCount()).isGreaterThanOrEqualTo(5);
        assertThat(document.chapters()).hasSize(1);
        assertThat(document.chapters().getFirst().title()).isEqualTo("Розділ один");
        assertThat(document.toc().entries()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void preservesWhitespaceAcrossInlineElementBoundaries() throws Exception {
        String fb2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info><book-title>Whitespace</book-title><lang>uk</lang></title-info></description>
                  <body><section>
                    <p>Перше <strong>виділене</strong> слово і <emphasis>ще</emphasis> текст.</p>
                    <p>Межа<strong>без пробілу</strong>має лишитися без штучного пробілу.</p>
                    <p>До <strong>тега</strong>   після.</p>
                  </section></body>
                </FictionBook>
                """;

        ReaderDocument document = new Fb2StreamingParser().parse(
                new MemoryBookSource(fb2), ParseOptions.withoutImages());

        assertThat(document.text().getFullText())
                .contains("Перше виділене слово і ще текст.")
                .contains("Межабез пробілумaє".replace('a', 'а'))
                .contains("До тега після.");
    }

    @Test
    void keepsNestedBoldItalicAndStreamsLargeBinaryResource() throws Exception {
        byte[] image = new byte[300_000];
        for (int i = 0; i < image.length; i++) {
            image[i] = (byte) (i * 13);
        }
        String base64 = Base64.getMimeEncoder(76, new byte[]{'\n'}).encodeToString(image);

        String fb2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" " +
                "xmlns:l=\"http://www.w3.org/1999/xlink\">" +
                "<description><title-info><book-title>Styled</book-title>" +
                "<author><first-name>A</first-name></author><lang>uk</lang></title-info></description>" +
                "<body><section><p>Normal <strong><emphasis>BI</emphasis></strong> end</p>" +
                "<image l:href=\"#cover\"/></section></body>" +
                "<binary id=\"cover\" content-type=\"image/jpeg\">" + base64 +
                "</binary></FictionBook>";

        ReaderDocument document = new Fb2StreamingParser().parse(
                new MemoryBookSource(fb2), ParseOptions.defaultOptions());

        assertThat(document.text().getSpans(0, document.text().length()))
                .extracting(span -> span.style())
                .contains(TextStyle.BOLD_ITALIC);
        assertThat(document.resources().exists("cover")).isTrue();
        assertThat(document.resources()).isInstanceOf(HybridResourceRepository.class);
        assertThat(((HybridResourceRepository) document.resources()).inMemorySize()).isLessThan(image.length);
        try (InputStream in = document.resources().open("cover").orElseThrow()) {
            assertThat(in.readAllBytes()).hasSize(image.length);
        }
    }

    @Test
    void preservesSemanticParagraphStylesForReaderThemeOverrides() throws Exception {
        String fb2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info><book-title>Semantic</book-title><lang>uk</lang></title-info></description>
                  <body>
                    <section>
                      <title><p>Розділ</p></title>
                      <epigraph><p>Епіграф</p><text-author>Автор епіграфа</text-author></epigraph>
                      <p>Звичайний <strong>жирний</strong> та <emphasis>курсивний</emphasis> текст.</p>
                      <poem><stanza><v>Рядок вірша</v></stanza><text-author>Автор вірша</text-author></poem>
                      <cite><p>Цитата</p></cite>
                      <annotation><p>Анотація в body</p></annotation>
                      <section><title><p>Підрозділ</p></title><p>Текст.</p></section>
                    </section>
                  </body>
                  <body name="notes"><section id="n1"><p>Текст примітки</p></section></body>
                </FictionBook>
                """;

        ReaderDocument document = new Fb2StreamingParser().parse(
                new MemoryBookSource(fb2), ParseOptions.defaultOptions());

        assertThat(document.text().getParagraphs())
                .extracting(paragraph -> paragraph.style())
                .contains(TextStyle.CHAPTER_TITLE, TextStyle.SECTION_TITLE, TextStyle.EPIGRAPH,
                        TextStyle.VERSE, TextStyle.POEM_AUTHOR, TextStyle.CITE, TextStyle.ANNOTATION, TextStyle.FOOTNOTE);
        assertThat(document.text().getSpans(0, document.text().length()))
                .extracting(span -> span.style())
                .contains(TextStyle.STRONG, TextStyle.EMPHASIS);
    }


    @Test
    void namedPrimaryBodyIsNotMistakenForFootnotesWhenInspectionSkipsNotes() throws Exception {
        String fb2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info><book-title>Named body</book-title><lang>ru</lang></title-info></description>
                  <body name="Возвращение Великого">
                    <section><title><p>Глава</p></title><p>Основной текст книги.</p></section>
                  </body>
                  <body name="notes"><section><p>Примечание.</p></section></body>
                </FictionBook>
                """;

        ReaderDocument document = new Fb2StreamingParser().parse(
                new MemoryBookSource(fb2), new ParseOptions(true, false, true, 1024 * 1024, null));

        assertThat(document.text().getFullText())
                .contains("Основной текст книги")
                .doesNotContain("Примечание");
        assertThat(document.toc().entries()).isNotEmpty();
    }

    private static final class MemoryBookSource implements BookSource {
        private final byte[] bytes;

        private MemoryBookSource(String xml) {
            bytes = xml.getBytes(StandardCharsets.UTF_8);
        }

        @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
        @Override public OptionalLong size() { return OptionalLong.of(bytes.length); }
        @Override public String name() { return "test.fb2"; }
        @Override public String extension() { return "fb2"; }
        @Override public String id() { return "test-book"; }
    }
}
