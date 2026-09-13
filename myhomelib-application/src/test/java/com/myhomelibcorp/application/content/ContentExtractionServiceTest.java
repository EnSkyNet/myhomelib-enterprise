package com.myhomelibcorp.application.content;

import com.myhomelibcorp.application.port.out.content.ContentExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ContentExtractionServiceTest {
    @Test
    void unsupportedFormatReturnsTypedResult() {
        ContentExtractionService service = new ContentExtractionService(List.of());
        ContentExtractionResult result = service.extract(
                new ContentExtractionRequest(source("book.pdf", "x"), "pdf"), ContentExtractionContext.none());
        assertThat(result.status()).isEqualTo(ContentExtractionStatus.UNSUPPORTED);
        assertThat(result.content()).isNull();
    }

    @Test
    void cancellationIsMappedToTypedResult() {
        ContentExtractor extractor = new ContentExtractor() {
            @Override public String id() { return "test"; }
            @Override public boolean supports(String format) { return "txt".equals(format); }
            @Override public ExtractedContent extract(ContentExtractionRequest request, ContentExtractionContext context) {
                context.throwIfCancelled();
                throw new AssertionError("must stop before extraction");
            }
        };
        AtomicBoolean cancelled = new AtomicBoolean(true);
        ContentExtractionResult result = new ContentExtractionService(List.of(extractor)).extract(
                new ContentExtractionRequest(source("book.txt", "x"), "txt"),
                ContentExtractionContext.create(cancelled, ignored -> { }));
        assertThat(result.status()).isEqualTo(ContentExtractionStatus.CANCELLED);
    }

    @Test
    void ioFailureIsMappedWithoutThrowing() {
        ContentExtractor extractor = new ContentExtractor() {
            @Override public String id() { return "broken"; }
            @Override public boolean supports(String format) { return true; }
            @Override public ExtractedContent extract(ContentExtractionRequest request, ContentExtractionContext context) throws IOException {
                throw new IOException("bad input");
            }
        };
        ContentExtractionResult result = new ContentExtractionService(List.of(extractor)).extract(
                ContentExtractionRequest.of(source("book.fb2", "x")), ContentExtractionContext.none());
        assertThat(result.status()).isEqualTo(ContentExtractionStatus.FAILED);
        assertThat(result.message()).contains("bad input");
    }

    private static ContentExtractionSource source(String name, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new ContentExtractionSource() {
            @Override public String id() { return "memory:" + name; }
            @Override public String name() { return name; }
            @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
            @Override public OptionalLong size() { return OptionalLong.of(bytes.length); }
        };
    }
}
