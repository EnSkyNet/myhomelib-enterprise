package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.graph.EpubResourceReplacementWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class EpubPackageVersionPromoterTest {
    @TempDir Path temp;

    @Test
    void promotesOnlyOpfPackageVersionAndPreservesPublicationResources() throws Exception {
        Path source = temp.resolve("source.epub");
        Path target = temp.resolve("target.epub");
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.3" unique-identifier="bookid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="bookid">urn:test</dc:identifier></metadata>
                  <manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c"/></spine>
                </package>
                """;
        byte[] chapter = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>Text</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        writeFixture(source, opf.getBytes(StandardCharsets.UTF_8), chapter);

        new EpubPackageVersionPromoter(new EpubResourceReplacementWriter())
                .promoteTo34(source, target, "OEBPS/content.opf", temp);

        try (ZipFile zip = new ZipFile(target.toFile(), StandardCharsets.UTF_8)) {
            String promoted = read(zip, "OEBPS/content.opf");
            assertThat(promoted).contains("version=\"3.4\"");
            assertThat(promoted).contains("unique-identifier=\"bookid\"");
            assertThat(zip.getInputStream(zip.getEntry("OEBPS/chapter.xhtml")).readAllBytes())
                    .containsExactly(chapter);
            assertThat(zip.entries().nextElement().getName()).isEqualTo("mimetype");
            assertThat(zip.getEntry("mimetype").getMethod()).isEqualTo(ZipEntry.STORED);
        }
    }

    private static String read(ZipFile zip, String name) throws Exception {
        try (InputStream input = zip.getInputStream(zip.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeFixture(Path target, byte[] opf, byte[] chapter) throws Exception {
        byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimetype.length);
            mime.setCompressedSize(mimetype.length);
            mime.setCrc(crc.getValue());
            out.putNextEntry(mime);
            out.write(mimetype);
            out.closeEntry();

            write(out, "OEBPS/content.opf", opf);
            write(out, "OEBPS/chapter.xhtml", chapter);
        }
    }

    private static void write(ZipOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
