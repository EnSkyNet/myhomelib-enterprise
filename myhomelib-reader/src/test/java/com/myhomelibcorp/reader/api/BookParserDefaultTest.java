package com.myhomelibcorp.reader.api;

import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BookParserDefaultTest {

    @Test
    void defaultMetadataReadHandlesMissingChaptersAndClosesDocumentResources() throws Exception {
        TextStorageImpl text = new TextStorageImpl();
        text.startParagraph(TextStyle.NORMAL);
        text.append("Metadata text", TextStyle.NORMAL);
        TrackingResources resources = new TrackingResources();

        ReaderDocument document = CompactReaderDocument.builder()
                .metadata(new BookMetadata("metadata", "Metadata", List.of("Author"), "en", null, null,
                        List.of(), "", "", "", null, text.length()))
                .chapters(null)
                .resources(resources)
                .text(text)
                .toc(new DefaultTableOfContents())
                .totalTextLength(text.length())
                .build();

        BookParser parser = new BookParser() {
            @Override
            public ReaderDocument parse(BookSource source, ParseOptions options) {
                return document;
            }
        };

        BookDocumentMetadata metadata = parser.readMetadata(null);

        assertThat(metadata.title()).isEqualTo("Metadata");
        assertThat(metadata.chapterCount()).isZero();
        assertThat(resources.closed).isTrue();
    }

    private static final class TrackingResources implements ResourceRepository, AutoCloseable {
        private boolean closed;

        @Override public Optional<ResourceInfo> getInfo(String id) { return Optional.empty(); }
        @Override public Optional<InputStream> open(String id) { return Optional.empty(); }
        @Override public Iterable<String> getAllIds() { return List.of(); }
        @Override public int count() { return 0; }
        @Override public long totalSize() { return 0; }
        @Override public void close() { closed = true; }
    }
}
