package com.myhomelibcorp.converter.toolchain.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatCode;

class EpubOutputValidatorDescriptorTest {
    @TempDir Path tempDir;

    @Test
    void acceptsFbcStyleStoredMimetypeDescriptorFlag() throws Exception {
        Path epub = tempDir.resolve("fbc-style.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub), StandardCharsets.UTF_8)) {
            byte[] mimeBytes = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32(); crc.update(mimeBytes);
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimeBytes.length);
            mime.setCompressedSize(mimeBytes.length);
            mime.setCrc(crc.getValue());
            zip.putNextEntry(mime); zip.write(mimeBytes); zip.closeEntry();
            write(zip, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            write(zip, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <manifest><item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="c1"/></spine>
                    </package>
                    """);
            write(zip, "OEBPS/chapter.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>ok</body></html>");
        }
        byte[] bytes = Files.readAllBytes(epub);
        bytes[6] = (byte) (bytes[6] | 0x08);
        Files.write(epub, bytes);

        assertThatCode(() -> new EpubOutputValidator().validate(epub, 32L * 1024L * 1024L))
                .doesNotThrowAnyException();
    }

    private static void write(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
