package com.myhomelibcorp.web;

import com.myhomelibcorp.application.webreader.WebReaderChapter;
import com.myhomelibcorp.application.webreader.WebReaderDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebReaderRendererTest {
    private final WebReaderRenderer renderer = new WebReaderRenderer();

    @Test void rendersTocThemeFontResumeAndProgressEndpointWithoutTrustingBookText() {
        WebReaderDocument doc = new WebReaderDocument("b 1", "<Book>", "epub", true, "", List.of(
                new WebReaderChapter(0, "c1", "First", 0, 10, "<script>x</script>\ntext"),
                new WebReaderChapter(1, "c2", "Second", 11, 20, "second")
        ), 0, 0, 5, 25);

        String html = renderer.render(doc);

        assertThat(html).contains("name=\"viewport\"", "Зміст", "id=theme", "id=font", "localStorage", "/web/read/b%201?chapter=1", "/progress");
        assertThat(html).contains("&lt;script&gt;x&lt;/script&gt;").doesNotContain("<script>x</script>");
        assertThat(html).contains("data-resume=\"5\"", "data-resume-chapter=\"0\"");
    }

    @Test void unsupportedFormatShowsDownloadInsteadOfFakeReader() {
        String html = renderer.render(new WebReaderDocument("42", "PDF", "pdf", false,
                "Unsupported", List.of(), 0, 0, 0, 0));
        assertThat(html).contains("Unsupported", "/web/download/42").doesNotContain("id=content");
    }
}
