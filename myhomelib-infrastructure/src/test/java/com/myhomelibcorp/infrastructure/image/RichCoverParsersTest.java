package com.myhomelibcorp.infrastructure.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RichCoverParsersTest {
    @TempDir Path temp;

    @Test
    void extractsEpubCoverFromOpfCoverImageProperty() throws Exception {
        byte[] cover = new byte[]{(byte)0xff,(byte)0xd8,1,2,3,(byte)0xff,(byte)0xd9};
        Path epub = temp.resolve("book.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
            entry(zip, "META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/content.opf\"/></rootfiles></container>".getBytes(StandardCharsets.UTF_8));
            entry(zip, "OPS/content.opf", "<package><manifest><item id=\"c\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/></manifest></package>".getBytes(StandardCharsets.UTF_8));
            entry(zip, "OPS/images/cover.jpg", cover);
        }
        assertThat(new EpubCoverParser().parse(epub)).containsExactly(cover);
    }

    @Test
    void extractsMobiCoverUsingExth201() throws Exception {
        byte[] jpeg = new byte[]{(byte)0xff,(byte)0xd8,5,6,(byte)0xff,(byte)0xd9};
        byte[] data = new byte[900];
        putU16(data, 76, 3);
        putU32(data, 78, 102);
        putU32(data, 86, 600);
        putU32(data, 94, 620);
        int r0 = 102;
        ascii(data, r0 + 16, "MOBI");
        putU32(data, r0 + 20, 232);
        putU32(data, r0 + 16 + 92, 1); // first image record
        putU32(data, r0 + 16 + 112, 0x40);
        int exth = r0 + 16 + 232;
        ascii(data, exth, "EXTH");
        putU32(data, exth + 4, 24);
        putU32(data, exth + 8, 1);
        putU32(data, exth + 12, 201);
        putU32(data, exth + 16, 12);
        putU32(data, exth + 20, 1); // cover is firstImageIndex + 1 = record 2
        System.arraycopy(jpeg, 0, data, 620, jpeg.length);
        Path mobi = temp.resolve("book.mobi");
        Files.write(mobi, data);
        assertThat(new MobiCoverParser().parse(mobi)).startsWith((byte)0xff, (byte)0xd8);
    }

    @Test
    void extractsDctImageFromSimplePdf() throws Exception {
        byte[] jpeg = new byte[]{(byte)0xff,(byte)0xd8,10,11,12,(byte)0xff,(byte)0xd9};
        byte[] prefix = "%PDF-1.4\n1 0 obj << /Subtype /Image /Filter /DCTDecode /Length 7 >>\nstream\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] suffix = "\nendstream\nendobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        byte[] pdf = new byte[prefix.length + jpeg.length + suffix.length];
        System.arraycopy(prefix,0,pdf,0,prefix.length); System.arraycopy(jpeg,0,pdf,prefix.length,jpeg.length); System.arraycopy(suffix,0,pdf,prefix.length+jpeg.length,suffix.length);
        Path file = temp.resolve("book.pdf"); Files.write(file,pdf);
        assertThat(new PdfCoverParser().parse(file)).containsExactly(jpeg);
    }

    @Test
    void fallbackCoverIsValidPngBytes() {
        byte[] bytes = new FallbackCoverRenderer().render("DJVU", "Demo");
        assertThat(bytes).isNotNull().hasSizeGreaterThan(100);
        assertThat(bytes[0]).isEqualTo((byte)0x89);
        assertThat(new String(bytes, 1, 3, StandardCharsets.US_ASCII)).isEqualTo("PNG");
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    private static void ascii(byte[] data, int pos, String value) { byte[] b=value.getBytes(StandardCharsets.US_ASCII); System.arraycopy(b,0,data,pos,b.length); }
    private static void putU16(byte[] data,int pos,int value){data[pos]=(byte)(value>>>8);data[pos+1]=(byte)value;}
    private static void putU32(byte[] data,int pos,int value){ByteBuffer.wrap(data,pos,4).order(ByteOrder.BIG_ENDIAN).putInt(value);}
}
