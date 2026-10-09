package com.myhomelibcorp.infrastructure.importer.streamarchive;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StreamArchiveImporterMultiEntryTest {
    @TempDir Path temp;

    @Test
    void malformedBookEntryDoesNotHideLaterValidBooks() throws Exception {
        Path tar = temp.resolve("mixed.tar");
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
            add(out, "bad.txt", "BAD");
            add(out, "good.txt", "Good");
        }

        StreamArchiveImporter importer = createImporter();
        List<Book> books;
        try (Stream<Book> stream = importer.importBooks(tar)) {
            books = stream.toList();
        }

        assertThat(books).extracting(Book::getTitle).containsExactly("Good");
        assertThat(books).extracting(Book::getArchiveEntry).containsExactly("good.txt");
    }

    private StreamArchiveImporter createImporter() throws Exception {
        BookImporterPort txt = new BookImporterPort() {
            @Override public boolean supports(Path file) {
                return file.toString().toLowerCase(Locale.ROOT).endsWith(".txt");
            }
            @Override public Stream<Book> importBooks(Path file) {
                try {
                    String title = Files.readString(file, StandardCharsets.UTF_8).trim();
                    if ("BAD".equals(title)) throw new IllegalArgumentException("malformed test book");
                    return Stream.of(Book.builder().title(title).file(BookFile.empty()).build());
                } catch (RuntimeException e) {
                    throw e;
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

        StreamArchiveImporter importer = new StreamArchiveImporter();
        Field field = StreamArchiveImporter.class.getDeclaredField("registry");
        field.setAccessible(true);
        field.set(importer, registry);
        return importer;
    }

    private static void add(TarArchiveOutputStream out, String name, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        out.putArchiveEntry(entry);
        out.write(bytes);
        out.closeArchiveEntry();
    }
}
