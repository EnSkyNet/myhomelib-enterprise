package com.myhomelibcorp.organizer.metadata;

import com.myhomelibcorp.organizer.detect.DetectedBookFormat;
import com.myhomelibcorp.organizer.detect.BookEntryNameSupport;
import com.myhomelibcorp.organizer.detect.DetectionConfidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizerMetadataExtractorTest {
    @TempDir Path tempDir;

    @Test
    void boundsLargeFb2AnnotationWithoutDroppingOtherMetadata() throws Exception {
        String annotation = "А".repeat(1024 * 1024 + 8192);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                + "<description><title-info><genre>prose</genre><author><first-name>Іван</first-name>"
                + "<last-name>Тест</last-name></author><book-title>Назва</book-title>"
                + "<annotation><p>" + annotation + "</p></annotation><lang>uk</lang></title-info></description>"
                + "<body><section><p>Текст</p></section></body></FictionBook>";
        Path fb2 = tempDir.resolve("large-annotation.fb2");
        Files.writeString(fb2, xml, StandardCharsets.UTF_8);

        ExtractedBookMetadata metadata = new OrganizerMetadataExtractor().extract(fb2,
                new DetectedBookFormat("fb2", "application/x-fictionbook+xml", DetectionConfidence.CERTAIN,
                        ".fb2", false, "fixture"));

        assertThat(metadata.rawTitle()).isEqualTo("Назва");
        assertThat(metadata.rawAuthors()).contains("Іван Тест");
        assertThat(metadata.annotation().length()).isLessThanOrEqualTo(1024 * 1024);
        assertThat(metadata.warnings()).contains("FB2 annotation was truncated to the metadata safety limit");
    }

    @Test
    void boundsOversizedSimpleMetadataFieldWhileContinuingParse() throws Exception {
        String hugeTitle = "X".repeat(300 * 1024);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                + "<description><title-info><book-title>" + hugeTitle + "</book-title><lang>uk</lang>"
                + "</title-info></description><body><section><p>Text</p></section></body></FictionBook>";
        Path fb2 = tempDir.resolve("large-title.fb2");
        Files.writeString(fb2, xml, StandardCharsets.UTF_8);

        ExtractedBookMetadata metadata = new OrganizerMetadataExtractor().extract(fb2,
                new DetectedBookFormat("fb2", "application/x-fictionbook+xml", DetectionConfidence.CERTAIN,
                        ".fb2", false, "fixture"));

        assertThat(metadata.rawTitle()).hasSize(256 * 1024);
        assertThat(metadata.language()).isEqualTo("uk");
        assertThat(metadata.warnings()).contains("Metadata field was truncated to the safety limit: book-title");
    }
    @Test
    void readsMobiExthMetadataForOrganizerPlanning() throws Exception {
        Path file = tempDir.resolve("sample.mobi");
        byte[] data = new byte[2048];
        putU16(data, 76, 1);
        putU32(data, 78, 86);
        int r0 = 86;
        putAscii(data, r0 + 16, "MOBI");
        putU32(data, r0 + 20, 232);
        putU32(data, r0 + 28, 65001);
        putU32(data, r0 + 16 + 112, 0x40);
        int exth = r0 + 16 + 232;
        putAscii(data, exth, "EXTH");
        byte[][] records = {
                exthRecord(503, "Organizer MOBI"),
                exthRecord(100, "Mobi Author"),
                exthRecord(101, "Publisher"),
                exthRecord(103, "<p>Summary</p>"),
                exthRecord(104, "9780000000001"),
                exthRecord(106, "2024-05-10"),
                exthRecord(524, "uk")
        };
        int length = 12;
        for (byte[] record : records) length += record.length;
        putU32(data, exth + 4, length);
        putU32(data, exth + 8, records.length);
        int pos = exth + 12;
        for (byte[] record : records) {
            System.arraycopy(record, 0, data, pos, record.length);
            pos += record.length;
        }
        Files.write(file, data);

        ExtractedBookMetadata metadata = new OrganizerMetadataExtractor().extract(file,
                new DetectedBookFormat("mobi", "application/x-mobipocket-ebook", DetectionConfidence.HIGH,
                        ".mobi", false, "fixture"));

        assertThat(metadata.rawTitle()).isEqualTo("Organizer MOBI");
        assertThat(metadata.rawAuthors()).containsExactly("Mobi Author");
        assertThat(metadata.publisher()).isEqualTo("Publisher");
        assertThat(metadata.isbn()).isEqualTo("9780000000001");
        assertThat(metadata.language()).isEqualTo("uk");
        assertThat(metadata.year()).isEqualTo(2024);
        assertThat(metadata.annotation()).isEqualTo("Summary");
    }

    @Test
    void readsOdtMetaXmlForOrganizerPlanning() throws Exception {
        Path file = tempDir.resolve("sample.odt");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            putZip(zip, "mimetype", "application/vnd.oasis.opendocument.text");
            putZip(zip, "meta.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                      xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0">
                      <office:meta><dc:title>ODT Title</dc:title><dc:creator>ODT Author</dc:creator>
                      <dc:language>uk</dc:language><dc:subject>ODT Subject</dc:subject>
                      <meta:initial-creator>Initial Author</meta:initial-creator></office:meta>
                    </office:document-meta>
                    """);
        }

        ExtractedBookMetadata metadata = new OrganizerMetadataExtractor().extract(file,
                new DetectedBookFormat("odt", "application/vnd.oasis.opendocument.text", DetectionConfidence.CERTAIN,
                        ".odt", true, "fixture"));

        assertThat(metadata.rawTitle()).isEqualTo("ODT Title");
        assertThat(metadata.rawAuthors()).contains("ODT Author");
        assertThat(metadata.language()).isEqualTo("uk");
        assertThat(metadata.annotation()).isEqualTo("ODT Subject");
    }

    @Test
    void readsPdfDocumentInformationForOrganizerPlanning() throws Exception {
        Path file = tempDir.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("PDF Title");
            info.setAuthor("PDF Author");
            info.setSubject("PDF Subject");
            info.setCustomMetadataValue("Publisher", "PDF Publisher");
            document.setDocumentInformation(info);
            document.save(file.toFile());
        }

        ExtractedBookMetadata metadata = new OrganizerMetadataExtractor().extract(file,
                new DetectedBookFormat("pdf", "application/pdf", DetectionConfidence.CERTAIN,
                        ".pdf", false, "fixture"));

        assertThat(metadata.rawTitle()).isEqualTo("PDF Title");
        assertThat(metadata.rawAuthors()).containsExactly("PDF Author");
        assertThat(metadata.publisher()).isEqualTo("PDF Publisher");
        assertThat(metadata.annotation()).isEqualTo("PDF Subject");
    }

    @Test
    void readsComicInfoFromCbzForOrganizerPlanning() throws Exception {
        Path file = tempDir.resolve("sample.cbz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            putZip(zip, "ComicInfo.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ComicInfo><Title>Comic Title</Title><Writer>Comic Author</Writer>
                    <Series>Comic Series</Series><Number>7</Number><LanguageISO>uk</LanguageISO>
                    <Publisher>Comic Publisher</Publisher><Summary>Comic Summary</Summary><Year>2025</Year></ComicInfo>
                    """);
            putZip(zip, "001.jpg", "not-an-image");
        }

        ExtractedBookMetadata metadata = new OrganizerMetadataExtractor().extract(file,
                new DetectedBookFormat("cbz", "application/zip", DetectionConfidence.HIGH,
                        ".cbz", true, "fixture"));

        assertThat(metadata.rawTitle()).isEqualTo("Comic Title");
        assertThat(metadata.rawAuthors()).containsExactly("Comic Author");
        assertThat(metadata.rawSeries()).isEqualTo("Comic Series");
        assertThat(metadata.rawSeriesNumber()).isEqualTo("7");
        assertThat(metadata.language()).isEqualTo("uk");
        assertThat(metadata.publisher()).isEqualTo("Comic Publisher");
        assertThat(metadata.annotation()).isEqualTo("Comic Summary");
        assertThat(metadata.year()).isEqualTo(2025);
    }

    private static void putZip(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static byte[] exthRecord(int type, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + text.length).order(ByteOrder.BIG_ENDIAN);
        b.putInt(type).putInt(8 + text.length).put(text);
        return b.array();
    }

    private static void putAscii(byte[] data, int pos, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, pos, bytes.length);
    }

    private static void putU16(byte[] data, int pos, int value) {
        data[pos] = (byte) (value >>> 8);
        data[pos + 1] = (byte) value;
    }

    private static void putU32(byte[] data, int pos, int value) {
        ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).putInt(value);
    }

    @Test
    void everyOrganizerRecognizedBookExtensionHasExplicitMetadataCoverage() throws Exception {
        OrganizerMetadataExtractor extractor = new OrganizerMetadataExtractor();
        for (String extension : BookEntryNameSupport.supportedExtensions()) {
            String detected = switch (extension) {
                case "md", "markdown" -> "markdown";
                case "htm", "html" -> "html";
                case "djv", "djvu" -> "djvu";
                default -> extension;
            };
            assertThat(extractor.coverage(detected))
                    .as("metadata coverage for supported extension %s -> %s", extension, detected)
                    .isNotEqualTo(MetadataExtractionLevel.UNCLASSIFIED);
        }
        assertThat(extractor.coverage("fb2_zip")).isEqualTo(MetadataExtractionLevel.STRUCTURED);
        assertThat(extractor.coverage("book_archive")).isEqualTo(MetadataExtractionLevel.NOT_APPLICABLE);
    }

}
