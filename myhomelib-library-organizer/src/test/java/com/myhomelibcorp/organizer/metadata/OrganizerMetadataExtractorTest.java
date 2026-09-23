package com.myhomelibcorp.organizer.metadata;

import com.myhomelibcorp.organizer.detect.DetectedBookFormat;
import com.myhomelibcorp.organizer.detect.DetectionConfidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizerMetadataExtractorTest {
    @TempDir Path tempDir;

    @Test
    void boundsLargeFb2AnnotationWithoutDroppingOtherMetadata() throws Exception {
        String annotation = "А".repeat(1024 * 1024 + 8192);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                + "<description><title-info><genre>prose</genre><author><first-name>Іван</first-name>"
                + "<last-name>Тест</last-name></author><book-title>Назва</book-title>"
                + "<annotation><p>" + annotation + "</p></annotation><lang>uk</lang></title-info></description>"
                + "<body><section><p>Текст</p></section></body></FictionBook>";
        Path fb2 = tempDir.resolve("large-annotation.fb2");
        Files.writeString(fb2, xml, StandardCharsets.UTF_8);

        ExtractedBookMetadata metadata = new OrganizerMetadataExtractor().extract(fb2,
                new DetectedBookFormat("fb2", "application/x-fictionbook+xml", DetectionConfidence.CERTAIN,
                        ".fb2", false, "fixture"));

        assertThat(metadata.rawTitle()).isEqualTo("Назва");
        assertThat(metadata.rawAuthors()).contains("Іван Тест");
        assertThat(metadata.annotation().length()).isLessThanOrEqualTo(1024 * 1024);
        assertThat(metadata.warnings()).contains("FB2 annotation was truncated to the metadata safety limit");
    }

    @Test
    void boundsOversizedSimpleMetadataFieldWhileContinuingParse() throws Exception {
        String hugeTitle = "X".repeat(300 * 1024);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                + "<description><title-info><book-title>" + hugeTitle + "</book-title><lang>uk</lang>"
                + "</title-info></description><body><section><p>Text</p></section></body></FictionBook>";
        Path fb2 = tempDir.resolve("large-title.fb2");
        Files.writeString(fb2, xml, StandardCharsets.UTF_8);

        ExtractedBookMetadata metadata = new OrganizerMetadataExtractor().extract(fb2,
                new DetectedBookFormat("fb2", "application/x-fictionbook+xml", DetectionConfidence.CERTAIN,
                        ".fb2", false, "fixture"));

        assertThat(metadata.rawTitle()).hasSize(256 * 1024);
        assertThat(metadata.language()).isEqualTo("uk");
        assertThat(metadata.warnings()).contains("Metadata field was truncated to the safety limit: book-title");
    }
}
