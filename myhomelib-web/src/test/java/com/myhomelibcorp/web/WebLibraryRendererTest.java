package com.myhomelibcorp.web;

import com.myhomelibcorp.application.opds.OpdsBookDto;
import com.myhomelibcorp.application.opds.OpdsPage;
import com.myhomelibcorp.application.dto.ContinueReadingItemDto;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class WebLibraryRendererTest {
    private final WebLibraryRenderer renderer = new WebLibraryRenderer();

    @Test void rendersResponsiveCatalogueSearchActionsAndEscapesUntrustedText() {
        var page = new OpdsPage<>(java.util.List.of(book("b 1", "<script>x</script>")), 2, 0, 1);
        String html = renderer.books("Бібліотека", "/web/", "needle", page);
        assertThat(html).contains("name=\"viewport\"", "/web/search", "Продовжити читання", "/web/books/b%201", "/web/download/b%201", "Далі →");
        assertThat(html).contains("&lt;script&gt;x&lt;/script&gt;").doesNotContain("<script>x</script>");
    }

    @Test void fiveHundredThousandBookCatalogueStillRendersOnlyBoundedPage() {
        var books = IntStream.range(0, 50).mapToObj(i -> book("b"+i, "Book "+i)).toList();
        String html = renderer.books("Бібліотека", "/web/", "", new OpdsPage<>(books, 500_000, 250_000, 50));
        assertThat(html).contains("Знайдено: 500000", "offset=250050", "offset=249950");
        assertThat(html.length()).isLessThan(80_000);
        assertThat(count(html, "class=card")).isEqualTo(50);
    }

    @Test void detailsContainDownloadAndOpenLinks() {
        String html=renderer.book(book("42","Title"));
        assertThat(html).contains("/web/download/42", "/web/read/42", "Завантажити", "Читати");
    }

    @Test void continueReadingShelfShowsProgressDeviceTimeAndEscapesMetadata() {
        String html = renderer.continueReading(List.of(new ContinueReadingItemDto(
                "b 1", "<Book>", "A & B", 61.25, "Chapter <2>",
                LocalDateTime.of(2026, 9, 12, 18, 5), "tablet<1>")));
        assertThat(html).contains("61.3%", "12.09.2026 18:05", "/web/read/b%201", "Останній пристрій")
                .contains("&lt;Book&gt;", "A &amp; B", "tablet&lt;1&gt;")
                .doesNotContain("<Book>", "tablet<1>");
    }

    private static OpdsBookDto book(String id,String title){return new OpdsBookDto(id,title,"Author","Series","uk",2026,"Annotation","FB2",true,"book.fb2","");}
    private static int count(String text,String needle){int n=0,p=0;while((p=text.indexOf(needle,p))>=0){n++;p+=needle.length();}return n;}
}
