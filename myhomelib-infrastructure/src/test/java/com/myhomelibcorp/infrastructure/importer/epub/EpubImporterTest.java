package com.myhomelibcorp.infrastructure.importer.epub;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.archive.SafeZipArchive;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EpubImporterTest {

    @TempDir
    Path tempDir;

    @Test
    void mixedContentMetadataIsConsumedWithoutLosingFollowingFields() throws Exception {
        Path epub = tempDir.resolve("mixed.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub), StandardCharsets.UTF_8)) {
            put(zip, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                      <rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            put(zip, "OPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Назва <b xmlns="urn:test">книги</b></dc:title>
                        <dc:creator>Дмитрий <span xmlns="urn:test">Дорничев</span></dc:creator>
                        <dc:language>ru</dc:language>
                        <dc:subject>Фантастика</dc:subject>
                        <meta property="belongs-to-collection">Королевство <i xmlns="urn:test">світів</i></meta>
                      </metadata>
                    </package>
                    """);
        }

        Book book;
        try (var books = new EpubImporter().importBooks(epub)) {
            book = books.findFirst().orElseThrow();
        }

        assertThat(book.getTitle()).isEqualTo("Назва книги");
        assertThat(book.getAuthors()).hasSize(1);
        assertThat(book.getAuthors().getFirst().getFullName()).contains("Дмитрий").contains("Дорничев");
        assertThat(book.getSeries()).isEqualTo("Королевство світів");
        assertThat(book.getGenres()).extracting(g -> g.getName()).containsExactly("Фантастика");
    }


    @Test
    void metadataIsScopedAndInvalidLanguageDoesNotInventUkrainian() throws Exception {
        Path epub = tempDir.resolve("scoped.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub), StandardCharsets.UTF_8)) {
            put(zip, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                      <rootfiles><rootfile full-path="OPS/CONTENT.OPF" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            put(zip, "OPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Правильна назва</dc:title>
                        <dc:creator>Іван Петренко</dc:creator>
                        <dc:language>not_a_language_!</dc:language>
                      </metadata>
                      <manifest><item id="fake"><title xmlns="">Не метадані</title></item></manifest>
                    </package>
                    """);
        }

        Book book;
        try (var books = new EpubImporter().importBooks(epub)) {
            book = books.findFirst().orElseThrow();
        }

        assertThat(book.getTitle()).isEqualTo("Правильна назва");
        assertThat(book.getLanguage().value()).isEqualTo("und");
    }


    @Test
    void nonFiniteSeriesIndexFallsBackToZeroInsteadOfOverflowing() throws Exception {
        Path epub = tempDir.resolve("non-finite-series.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub), StandardCharsets.UTF_8)) {
            put(zip, "META-INF/container.xml", """
                    <container><rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles></container>
                    """);
            put(zip, "OPS/content.opf", """
                    <package><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:title>Series</dc:title>
                      <meta name="calibre:series" content="Saga"/>
                      <meta name="calibre:series_index" content="Infinity"/>
                    </metadata></package>
                    """);
        }

        Book book;
        try (var books = new EpubImporter().importBooks(epub)) {
            book = books.findFirst().orElseThrow();
        }

        assertThat(book.getSeries()).isEqualTo("Saga");
        assertThat(book.getSequenceNumber()).isZero();
    }

    @Test
    void rejectsArchiveWhoseDeclaredEntriesExceedCumulativeSafetyLimit() throws Exception {
        Path epub = tempDir.resolve("cumulative-bomb.epub");
        Files.write(epub, new byte[]{0});
        Path safePath = epub.toAbsolutePath().normalize();
        SafeZipArchive opened = mock(SafeZipArchive.class);
        ZipFile zip = mock(ZipFile.class);
        long entryBytes = 400L * 1024L * 1024L;
        long compressedBytes = 5L * 1024L * 1024L;
        List<ZipArchiveEntry> entries = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> {
                    ZipArchiveEntry entry = new ZipArchiveEntry("OPS/chapter-" + i + ".xhtml");
                    entry.setSize(entryBytes);
                    entry.setCompressedSize(compressedBytes);
                    return entry;
                })
                .toList();
        when(opened.zip()).thenReturn(zip);
        when(zip.getEntries()).thenAnswer(call -> Collections.enumeration(entries));

        try (var support = mockStatic(SafeZipArchive.class)) {
            support.when(() -> SafeZipArchive.open(safePath, StandardCharsets.UTF_8, "EPUB source"))
                    .thenReturn(opened);

            assertThatThrownBy(() -> new EpubImporter().importBooks(epub))
                    .isInstanceOf(RuntimeException.class)
                    .hasRootCauseInstanceOf(IOException.class)
                    .rootCause()
                    .hasMessageContaining("cumulative")
                    .hasMessageContaining("safety limit");
            verify(opened).close();
        }
    }

    @Test
    void malformedContainerIsReportedInsteadOfImportingPlaceholderMetadata() throws Exception {
        Path epub = tempDir.resolve("broken.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub), StandardCharsets.UTF_8)) {
            put(zip, "META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/content.opf\"></rootfiles>");
            put(zip, "OPS/content.opf", "<package><metadata/></package>");
        }

        assertThatThrownBy(() -> {
            try (var ignored = new EpubImporter().importBooks(epub)) {
                ignored.findFirst();
            }
        }).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Помилка імпорту EPUB");
    }

    private static void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
