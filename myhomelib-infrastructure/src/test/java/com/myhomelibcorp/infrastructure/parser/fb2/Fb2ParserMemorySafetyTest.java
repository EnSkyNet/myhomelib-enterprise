package com.myhomelibcorp.infrastructure.parser.fb2;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Fb2ParserMemorySafetyTest {
    @Test
    void boundsMetadataFieldsAndAnnotationWhileContinuingToReadOtherMetadata() throws Exception {
        String xml = """
                <FictionBook><description><title-info>
                <book-title>%s</book-title>
                <annotation><p>%s</p></annotation>
                <lang>en-US</lang><sequence name="Series" number="5"/>
                </title-info></description></FictionBook>
                """.formatted("T".repeat(20_000), "A".repeat(1_000_000));
        var result = new Fb2Parser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.getTitle()).hasSize(4_096);
        assertThat(result.getAnnotation()).hasSize(256 * 1024);
        assertThat(result.getLanguage()).isEqualTo("en-us");
        assertThat(result.getSeries()).isEqualTo("Series");
        assertThat(result.getSequenceNumber()).isEqualTo(5);
    }

    @Test
    void rejectsNestedMarkupInSimpleTextWithoutLeavingCorruptedState() {
        String xml = """
                <FictionBook><description><title-info>
                <book-title><nested>Invalid</nested></book-title>
                </title-info></description></FictionBook>
                """;
        assertThrows(Exception.class, () -> new Fb2Parser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }
}
