package com.myhomelibcorp.shared.comic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComicPageNameSupportTest {
    @Test
    void filtersUnsafeAndUnsupportedEntriesAndUsesNaturalOrder() {
        List<String> sorted = ComicPageNameSupport.sortPages(List.of(
                "10.jpg", "2.jpg", "001.jpg", "1.jpg", "cover.PNG", "notes.txt",
                "../escape.jpg", "/absolute.png", "folder/3.jpeg", "folder/.hidden.jpg"));

        assertThat(sorted).containsExactly("1.jpg", "001.jpg", "2.jpg", "10.jpg", "cover.PNG", "folder/3.jpeg");
    }

    @Test
    void acceptsCommonJavaImageIoPageFormatsCaseInsensitively() {
        assertThat(ComicPageNameSupport.isSupportedPage("p1.JPG")).isTrue();
        assertThat(ComicPageNameSupport.isSupportedPage("p2.jpeg")).isTrue();
        assertThat(ComicPageNameSupport.isSupportedPage("p3.png")).isTrue();
        assertThat(ComicPageNameSupport.isSupportedPage("p4.GIF")).isTrue();
        assertThat(ComicPageNameSupport.isSupportedPage("p5.bmp")).isTrue();
        assertThat(ComicPageNameSupport.isSupportedPage("p6.webp")).isFalse();
    }
}
