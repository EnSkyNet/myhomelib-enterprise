package com.myhomelibcorp.reader.inspection;

import com.myhomelibcorp.reader.api.FileBookSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class BookInspectionServiceTest {
    @TempDir Path temp;

    @Test
    void fb2InspectionProvidesSourceLanguageTocMetricsAndLazyImages() throws Exception {
        byte[] tinyPng = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=");
        String fb2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
                  <description><title-info>
                    <genre>prose</genre><author><first-name>Ada</first-name><last-name>Writer</last-name></author>
                    <book-title>Inspection Demo</book-title><lang>uk</lang><src-lang>en</src-lang>
                    <annotation><p>Catalog fallback annotation.</p></annotation>
                  </title-info></description>
                  <body><section><title><p>Chapter One</p></title><p>One two three four five.</p><image l:href="#img1"/></section></body>
                  <binary id="img1" content-type="image/png">%s</binary>
                </FictionBook>
                """.formatted(Base64.getEncoder().encodeToString(tinyPng));
        Path file = temp.resolve("demo.fb2");
        Files.writeString(file, fb2, StandardCharsets.UTF_8);

        try (DocumentInspectionSession session = new BookInspectionService().inspect(new FileBookSource(file))) {
            DocumentInspection result = session.inspection();
            assertThat(result.parsed()).isTrue();
            assertThat(result.sourceLanguage()).isEqualTo("en");
            assertThat(result.wordCount()).isGreaterThanOrEqualTo(5);
            assertThat(result.characterCount()).isGreaterThan(0);
            assertThat(result.tocPreview()).extracting(TocPreviewEntry::title).contains("Chapter One");
            assertThat(result.images()).isNotEmpty();
            assertThat(session.openImage(result.images().getFirst().id())).isPresent();
        }
    }
}
