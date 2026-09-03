package com.myhomelibcorp.infrastructure.importer.zip;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipImporterMultiEntryTest {
    @TempDir Path temp;

    @Test
    void importsEverySupportedEntryWithoutClosingZipAfterFirstBook() throws Exception {
        Path zip = temp.resolve("library.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            add(out, "one.txt", "One");
            add(out, "skip.bin", "ignored");
            add(out, "two.txt", "Two");
        }

        ZipImporter importer = createImporter();
        assertThat(importer.countBooks(zip)).isEqualTo(2);

        List<Book> books;
        try (Stream<Book> stream = importer.importBooks(zip)) {
            books = stream.toList();
        }

        assertThat(books).extracting(Book::getTitle).containsExactly("One", "Two");
        assertThat(books).extracting(Book::getArchiveEntry).containsExactly("one.txt", "two.txt");
    }

    @Test
    void detectsWindows1251EntryNamesInsteadOfPersistingMojibake() throws Exception {
        assertLegacyEntryName(Charset.forName("Windows-1251"), "Книги/Дмитрий Дорничев.txt");
    }

    @Test
    void detectsCp866EntryNamesInsteadOfPersistingMojibake() throws Exception {
        assertLegacyEntryName(Charset.forName("CP866"), "Книги/Дмитрий Дорничев.txt");
    }

    @Test
    void corruptZipIsReportedInsteadOfLookingLikeAnEmptyArchive() throws Exception {
        Path zip = temp.resolve("broken.zip");
        Files.writeString(zip, "this is not a zip", StandardCharsets.UTF_8);
        ZipImporter importer = createImporter();

        assertThat(importer.countBooks(zip)).isEqualTo(-1);
        assertThatThrownBy(() -> importer.importBooks(zip))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Не вдалося відкрити ZIP-архів");
    }

    private void assertLegacyEntryName(Charset charset, String entryName) throws Exception {
        Path zip = temp.resolve("legacy-" + charset.name().replaceAll("[^A-Za-z0-9]", "_") + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), charset)) {
            add(out, entryName, "Legacy");
        }
        ZipImporter importer = createImporter();
        List<Book> books;
        try (Stream<Book> stream = importer.importBooks(zip)) {
            books = stream.toList();
        }
        assertThat(books).hasSize(1);
        assertThat(books.getFirst().getArchiveEntry()).isEqualTo(entryName);
    }

    private ZipImporter createImporter() throws Exception {
        BookImporterPort txt = new BookImporterPort() {
            @Override public boolean supports(Path file) { return file.toString().toLowerCase().endsWith(".txt"); }
            @Override public Stream<Book> importBooks(Path file) {
                try {
                    String title = Files.readString(file, StandardCharsets.UTF_8).trim();
                    return Stream.of(Book.builder().title(title).file(BookFile.empty()).build());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            @Override public String getFormatName() { return "TXT-test"; }
            @Override public long countBooks(Path file) { return 1; }
        };
        ImporterRegistry registry = new ImporterRegistry() {
            @Override public BookImporterPort findImporter(Path file) {
                if (txt.supports(file)) return txt;
                throw new IllegalArgumentException("unsupported");
            }
            @Override public List<BookImporterPort> getAllImporters() { return List.of(txt); }
            @Override public List<String> getSupportedFormats() { return List.of("TXT-test"); }
        };

        ZipImporter importer = new ZipImporter();
        Field field = ZipImporter.class.getDeclaredField("importerRegistry");
        field.setAccessible(true);
        field.set(importer, registry);
        return importer;
    }

    private static void add(ZipOutputStream out, String name, String body) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
