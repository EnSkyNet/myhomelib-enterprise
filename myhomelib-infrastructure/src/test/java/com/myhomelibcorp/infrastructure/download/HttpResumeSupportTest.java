package com.myhomelibcorp.infrastructure.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpResumeSupportTest {

    @Test
    void acceptsConsistentPartialRange() {
        HttpResponse<?> response = response(206, Map.of(
                "Content-Range", List.of("bytes 6-10/11"),
                "Content-Length", List.of("5")));

        assertThatCode(() -> HttpResumeSupport.validateContentRange(response, 6, URI.create("https://example.test/book")))
                .doesNotThrowAnyException();
        assertThat(HttpResumeSupport.expectedTotal(response, 6)).isEqualTo(11);
    }

    @Test
    void rejectsInvertedOrOutOfTotalPartialRanges() {
        URI uri = URI.create("https://example.test/book");
        HttpResponse<?> inverted = response(206, Map.of("Content-Range", List.of("bytes 6-5/11")));
        HttpResponse<?> pastTotal = response(206, Map.of("Content-Range", List.of("bytes 6-11/11")));

        assertThatThrownBy(() -> HttpResumeSupport.validateContentRange(inverted, 6, uri))
                .isInstanceOf(IOException.class).hasMessageContaining("Content-Range");
        assertThatThrownBy(() -> HttpResumeSupport.validateContentRange(pastTotal, 6, uri))
                .isInstanceOf(IOException.class).hasMessageContaining("Content-Range");
    }

    @Test
    void rejectsRangeWhoseDeclaredBodyLengthDoesNotMatch() {
        HttpResponse<?> response = response(206, Map.of(
                "Content-Range", List.of("bytes 6-10/11"),
                "Content-Length", List.of("4")));

        assertThatThrownBy(() -> HttpResumeSupport.validateContentRange(
                response, 6, URI.create("https://example.test/book")))
                .isInstanceOf(IOException.class).hasMessageContaining("Content-Range");
    }

    @Test
    void weakEtagUsesLastModifiedForByteRangeResume(@TempDir Path tempDir) throws Exception {
        URI uri = URI.create("https://example.test/book");
        HttpResponse<?> response = response(200, Map.of(
                "ETag", List.of("W/\"entity-v1\""),
                "Last-Modified", List.of("Mon, 21 Sep 2026 10:00:00 GMT")));
        Path meta = tempDir.resolve("book.part.meta");

        HttpResumeSupport.ResumeMetadata written = HttpResumeSupport.write(meta, uri, response, null);
        HttpResumeSupport.ResumeMetadata reread = HttpResumeSupport.read(meta, uri);

        assertThat(written.validator()).isEqualTo("Mon, 21 Sep 2026 10:00:00 GMT");
        assertThat(reread.validator()).isEqualTo("Mon, 21 Sep 2026 10:00:00 GMT");
    }

    @Test
    void malformedUnquotedEtagUsesLastModifiedInstead(@TempDir Path tempDir) throws Exception {
        URI uri = URI.create("https://example.test/book");
        HttpResponse<?> response = response(200, Map.of(
                "ETag", List.of("entity-v1"),
                "Last-Modified", List.of("Mon, 21 Sep 2026 10:00:00 GMT")));
        Path meta = tempDir.resolve("book.part.meta");

        HttpResumeSupport.ResumeMetadata written = HttpResumeSupport.write(meta, uri, response, null);

        assertThat(written.validator()).isEqualTo("Mon, 21 Sep 2026 10:00:00 GMT");
    }

    @Test
    void malformedUnquotedEtagWithoutLastModifiedDisablesResume(@TempDir Path tempDir) throws Exception {
        URI uri = URI.create("https://example.test/book");
        HttpResponse<?> response = response(200, Map.of("ETag", List.of("entity-v1")));
        Path meta = tempDir.resolve("book.part.meta");

        HttpResumeSupport.ResumeMetadata written = HttpResumeSupport.write(meta, uri, response, null);

        assertThat(written.validator()).isBlank();
    }

    @Test
    void weakEtagWithoutLastModifiedDisablesByteRangeResume(@TempDir Path tempDir) throws Exception {
        URI uri = URI.create("https://example.test/book");
        HttpResponse<?> response = response(200, Map.of("ETag", List.of("W/\"entity-v1\"")));
        Path meta = tempDir.resolve("book.part.meta");

        HttpResumeSupport.ResumeMetadata written = HttpResumeSupport.write(meta, uri, response, null);

        assertThat(written.validator()).isBlank();
    }

    @Test
    void rejectsChangedEtagOnPartialResponse() {
        HttpResponse<?> response = response(206, Map.of(
                "Content-Range", List.of("bytes 6-10/11"),
                "Content-Length", List.of("5"),
                "ETag", List.of("\"entity-v2\"")));

        assertThatThrownBy(() -> HttpResumeSupport.validateResumeResponse(
                response, 6, "\"entity-v1\"", URI.create("https://example.test/book")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("validator");
    }

    @Test
    void rejectsChangedLastModifiedOnPartialResponse() {
        HttpResponse<?> response = response(206, Map.of(
                "Content-Range", List.of("bytes 6-10/11"),
                "Content-Length", List.of("5"),
                "Last-Modified", List.of("Tue, 22 Sep 2026 10:00:00 GMT")));

        assertThatThrownBy(() -> HttpResumeSupport.validateResumeResponse(
                response, 6, "Mon, 21 Sep 2026 10:00:00 GMT", URI.create("https://example.test/book")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("validator");
    }


    @Test
    void malformedRangeNeverEchoesUntrustedHeaderOrUrlCredentials() {
        URI uri = URI.create("https://user:SECRET_PASSWORD@example.test/book?api%5Fkey=SECRET_QUERY");
        for (String header : List.of(
                "bytes 0-1/2;token=SECRET_HEADER",
                "Bearer SECRET_HEADER",
                "not-a-range SECRET_HEADER")) {
            HttpResponse<?> response = response(206, Map.of("Content-Range", List.of(header)));
            assertThatThrownBy(() -> HttpResumeSupport.validateContentRange(response, 0, uri))
                    .isInstanceOf(IOException.class)
                    .satisfies(error -> assertThat(error.getMessage())
                            .contains("Content-Range")
                            .doesNotContain("SECRET_HEADER", "SECRET_PASSWORD", "SECRET_QUERY"));
        }
    }

    @Test
    void expectedTotalSaturatesOnHeaderArithmeticOverflow() {
        HttpResponse<?> response = response(200, Map.of("Content-Length", List.of(Long.toString(Long.MAX_VALUE))));

        assertThat(HttpResumeSupport.expectedTotal(response, 1)).isEqualTo(Long.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<?> response(int status, Map<String, List<String>> headers) {
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }
}
