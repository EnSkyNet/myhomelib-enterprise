package com.myhomelibcorp.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookContentReaderEpubTest {
    @TempDir Path temp;

    @Test
    void followsPackageSpineInsteadOfAlphabeticalFilenames() throws Exception {
        Path epub = createEpub();
        BookContentReader reader = new BookContentReader();
        LibraryDb.BookLocation location = new LibraryDb.BookLocation(
                "test", epub.getFileName().toString(), "", "", temp.toString());

        String text = reader.text(location);
        assertTrue(text.indexOf("FIRST-SPINE") < text.indexOf("SECOND-SPINE"), text);
    }

    @Test
    void returnsEpubNavTableOfContents() throws Exception {
        Path epub = createEpub();
        BookContentReader reader = new BookContentReader();
        LibraryDb.BookLocation location = new LibraryDb.BookLocation(
                "test", epub.getFileName().toString(), "", "", temp.toString());

        List<BookContentReader.TocItem> toc = reader.toc(location);
        assertEquals(2, toc.size());
        assertEquals("Chapter Two", toc.get(0).title());
        assertEquals("Chapter One", toc.get(1).title());
    }

    private Path createEpub() throws Exception {
        Path epub = temp.resolve("spine.epub");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(epub))) {
            put(out, "mimetype", "application/epub+zip");
            put(out, "META-INF/container.xml", """
                    <?xml version="1.0"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            put(out, "OPS/package.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <manifest>
                        <item id="one" href="a.xhtml" media-type="application/xhtml+xml"/>
                        <item id="two" href="z.xhtml" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                      </manifest>
                      <spine><itemref idref="two"/><itemref idref="one"/></spine>
                    </package>
                    """);
            put(out, "OPS/a.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>SECOND-SPINE</p></body></html>");
            put(out, "OPS/z.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>FIRST-SPINE</p></body></html>");
            put(out, "OPS/nav.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                      <body><nav epub:type="toc"><ol>
                        <li><a href="z.xhtml">Chapter Two</a></li>
                        <li><a href="a.xhtml">Chapter One</a></li>
                      </ol></nav></body>
                    </html>
                    """);
        }
        return epub;
    }

    private void put(ZipOutputStream out, String name, String body) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
