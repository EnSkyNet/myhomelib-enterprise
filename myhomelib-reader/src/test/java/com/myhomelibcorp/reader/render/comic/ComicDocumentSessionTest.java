package com.myhomelibcorp.reader.render.comic;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ComicDocumentSessionTest {
    @Test
    void opensLazilySortsPagesAndCachesRenderedViewport() throws Exception {
        byte[] png = png(64, 48);
        AtomicInteger opens = new AtomicInteger();
        ComicPageSource source = new ComicPageSource() {
            @Override public List<String> listPageEntries() { return List.of("10.png", "2.png", "1.png", "notes.txt"); }
            @Override public InputStream openPage(String entryName) {
                opens.incrementAndGet();
                return new ByteArrayInputStream(png);
            }
        };

        try (ComicDocumentSession session = ComicDocumentSession.open(source)) {
            assertThat(session.pageCount()).isEqualTo(3);
            assertThat(session.pageName(0)).isEqualTo("1.png");
            assertThat(session.pageName(1)).isEqualTo("2.png");
            assertThat(session.pageName(2)).isEqualTo("10.png");
            assertThat(opens).hasValue(0);

            ComicRenderedPage first = session.render(0, 800, 1000);
            ComicRenderedPage cached = session.render(0, 800, 1000);
            assertThat(first.width()).isEqualTo(64);
            assertThat(first.height()).isEqualTo(48);
            assertThat(cached).isSameAs(first);
            assertThat(opens).hasValue(1);

            session.render(1, 128, 128);
            assertThat(opens).hasValue(2);
        }
    }

    @Test
    void rejectsArchiveWithoutImagePages() {
        ComicPageSource source = new ComicPageSource() {
            @Override public List<String> listPageEntries() { return List.of("readme.txt"); }
            @Override public InputStream openPage(String entryName) throws IOException { throw new IOException("unused"); }
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ComicDocumentSession.open(source))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no supported image pages");
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
