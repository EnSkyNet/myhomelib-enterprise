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

        assertThat(html).contains("name=\"viewport\"", "Зміст", "id=theme", "id=font", "localStorage", "/web/read/b%201?chapter=1",
                "rel=\"manifest\" href=\"/web/app.webmanifest\"", WebPwaAssets.CLIENT_JS_PATH,
                "data-pwa-save-book=\"b 1\"", "data-pwa-remove-book=\"b 1\"", "data-pwa-clear-books",
                "MyHomeLibPwa.queueProgress");
        assertThat(html).doesNotContain("fetch('/web/read/'+encodeURIComponent(c.dataset.book)+'/progress");
        assertThat(html).contains("&lt;script&gt;x&lt;/script&gt;").doesNotContain("<script>x</script>");
        assertThat(html).contains("data-resume=\"5\"", "data-resume-chapter=\"0\"");
    }

    @Test void unsupportedFormatShowsDownloadInsteadOfFakeReader() {
        String html = renderer.render("en", new WebReaderDocument("42", "PDF", "pdf", false,
                "web.reader.unsupported_format", List.of(), 0, 0, 0, 0));
        assertThat(html).contains("not supported by Web Reader", "Download file", "/web/download/42")
                .doesNotContain("id=content", "не підтримується");
    }

    @Test void localizesReaderChromeAndHtmlLanguageFromRequestedLocale() {
        WebReaderDocument doc = new WebReaderDocument("1", "Book", "epub", true, "", List.of(
                new WebReaderChapter(0, "c1", "", 0, 10, "text")
        ), 0, 0, 0, 0);

        String en = renderer.render("en-US", doc);
        String bg = renderer.render("bg", doc);

        assertThat(en).contains("<html lang=\"en\"", "Contents", "Theme", "Light", "Font", "Chapter 1")
                .doesNotContain("Зміст", "Розділ 1");
        assertThat(bg).contains("<html lang=\"bg\"", "Съдържание", "Тема", "Светла", "Шрифт", "Глава 1");
    }
}
