package com.myhomelibcorp.reader.format.epub;

import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ReaderDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class EpubParserTest {
    @TempDir Path temp;

    @Test
    void followsSpineUsesNavAndLoadsEmbeddedImage() throws Exception {
        Path epub = temp.resolve("book.epub");
        try (OutputStream raw = java.nio.file.Files.newOutputStream(epub); ZipOutputStream zip = new ZipOutputStream(raw)) {
            put(zip, "META-INF/container.xml", """
                    <?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>""");
            put(zip, "OEBPS/content.opf", """
                    <?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:title>Spine Test</dc:title><dc:creator>Автор</dc:creator><dc:language>uk</dc:language>
                    </metadata>
                    <manifest>
                      <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                      <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                      <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                      <item id="img" href="img/pixel.png" media-type="image/png"/>
                    </manifest>
                    <spine><itemref idref="b"/><itemref idref="a"/></spine>
                    </package>""");
            put(zip, "OEBPS/nav.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                    <nav epub:type="toc"><ol><li><a href="b.xhtml">Другий файл — перший у spine</a></li>
                    <li><a href="a.xhtml">Перший файл — другий у spine</a></li></ol></nav></body></html>""");
            put(zip, "OEBPS/b.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Chapter B</h1><p>SPINE_FIRST</p><img src=\"img/pixel.png\"/></body></html>");
            put(zip, "OEBPS/a.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Chapter A</h1><p>SPINE_SECOND</p></body></html>");
            zip.putNextEntry(new ZipEntry("OEBPS/img/pixel.png"));
            zip.write(new byte[]{(byte)137,80,78,71,13,10,26,10,0,0,0,0});
            zip.closeEntry();
        }

        ReaderDocument doc = new EpubParser().parse(new FileBookSource(epub, "epub-test"));
        String text = doc.text().getFullText();

        assertThat(doc.metadata().title()).isEqualTo("Spine Test");
        assertThat(doc.metadata().authors()).containsExactly("Автор");
        assertThat(text.indexOf("SPINE_FIRST")).isLessThan(text.indexOf("SPINE_SECOND"));
        assertThat(doc.chapters()).hasSize(2);
        assertThat(doc.toc().entries()).extracting(e -> e.title())
                .containsExactly("Другий файл — перший у spine", "Перший файл — другий у spine");
        assertThat(doc.resources().exists("OEBPS/img/pixel.png")).isTrue();
        assertThat(text).contains("[IMAGE:OEBPS/img/pixel.png]");
    }

    @Test
    void supportsEpub2NcxWhenNavIsMissing() throws Exception {
        Path epub = temp.resolve("epub2.epub");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(epub))) {
            put(zip, "META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>");
            put(zip, "OPS/book.opf", """
                    <package><metadata><title>EPUB2</title></metadata><manifest>
                    <item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    </manifest><spine toc="ncx"><itemref idref="c"/></spine></package>""");
            put(zip, "OPS/chapter.xhtml", "<html><body><p>NCX_TEXT</p></body></html>");
            put(zip, "OPS/toc.ncx", "<ncx><navMap><navPoint><navLabel><text>NCX chapter</text></navLabel><content src=\"chapter.xhtml\"/></navPoint></navMap></ncx>");
        }
        ReaderDocument doc = new EpubParser().parse(new FileBookSource(epub));
        assertThat(doc.toc().entries()).extracting(e -> e.title()).containsExactly("NCX chapter");
    }

    @Test
    void resolvesEpub3AndNcxFragmentsToExactTextOffsets() throws Exception {
        Path epub = temp.resolve("anchors.epub");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(epub))) {
            put(zip, "META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>");
            put(zip, "OPS/book.opf", """
                    <package><metadata><title>Anchors</title><language>uk</language></metadata><manifest>
                    <item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    </manifest><spine><itemref idref="c"/></spine></package>""");
            put(zip, "OPS/nav.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                    <nav epub:type="toc"><ol><li><a href="chapter.xhtml#one">One</a></li>
                    <li><a href="chapter.xhtml#two">Two</a></li></ol></nav></body></html>""");
            put(zip, "OPS/chapter.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml"><body>
                    <h1 id="one">First</h1><p>FIRST_TEXT</p>
                    <h2 id="two">Second</h2><p>SECOND_TEXT</p>
                    </body></html>""");
        }
        ReaderDocument doc = new EpubParser().parse(new FileBookSource(epub));
        assertThat(doc.toc().entries()).hasSize(2);
        assertThat(doc.toc().entries().get(0).textOffset()).isLessThan(doc.toc().entries().get(1).textOffset());
        assertThat(doc.toc().entries().get(1).textOffset()).isGreaterThan(0);
    }

    private void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
