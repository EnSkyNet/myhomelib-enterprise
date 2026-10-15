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
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void lateParserFailureDoesNotLeakPartialBooksFromSameTarMember() throws Exception {
        Path tar = temp.resolve("partial.tar");
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
            add(out, "partial.txt", "PARTIAL");
            add(out, "good.txt", "Good");
        }
        StreamArchiveImporter importer = createImporter();
        try (Stream<Book> stream = importer.importBooks(tar)) {
            assertThat(stream.toList()).extracting(Book::getTitle).containsExactly("Good");
        }
    }

    @Test
    void skippedNonBookEntriesCountTowardCumulativeBudgetDuringImport() throws Exception {
        Path tar = temp.resolve("skipped-budget.tar");
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
            add(out, "notes.bin", "1234567");
            add(out, "good.txt", "Good");
        }

        StreamArchiveImporter importer = createImporter(10, 10);
        assertThatThrownBy(() -> {
            try (Stream<Book> stream = importer.importBooks(tar)) {
                stream.toList();
            }
        }).isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("Archive exceeds cumulative decompression safety limit");
    }

    @Test
    void countBooksReturnsUnknownWhenSkippedEntriesExceedCumulativeBudget() throws Exception {
        Path tar = temp.resolve("count-skipped-budget.tar");
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
            add(out, "notes.bin", "1234567");
            add(out, "good.txt", "Good");
        }

        StreamArchiveImporter importer = createImporter(10, 10);
        assertThat(importer.countBooks(tar)).isEqualTo(-1);
    }

    @Test
    void oversizedSkippedEntryIsBoundedInsteadOfImplicitlyDrained() throws Exception {
        Path tar = temp.resolve("oversized-skipped.tar");
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
            add(out, "notes.bin", "123456");
            add(out, "good.txt", "Good");
        }

        StreamArchiveImporter importer = createImporter(5, 20);
        assertThatThrownBy(() -> {
            try (Stream<Book> stream = importer.importBooks(tar)) {
                stream.toList();
            }
        }).isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("Archive entry exceeds per-entry decompression safety limit");
    }

    @Test
    void skippedEntriesWithinBudgetAreDrainedAndLaterBooksRemainAvailable() throws Exception {
        Path tar = temp.resolve("skipped-within-budget.tar");
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
            add(out, "notes.bin", "1234567");
            add(out, "good.txt", "Good");
        }

        StreamArchiveImporter importer = createImporter(10, 11);
        try (Stream<Book> stream = importer.importBooks(tar)) {
            assertThat(stream.toList()).extracting(Book::getTitle).containsExactly("Good");
        }
        assertThat(importer.countBooks(tar)).isEqualTo(1);
    }

    @Test
    void truncatedTarDoesNotSilentlyReportSuccessfullyImportedPrefix() throws Exception {
        Path tar = temp.resolve("truncated.tar");
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
            add(out, "first.txt", "First");
            add(out, "second.txt", "SECONDPAYLOAD_FOR_TRUNCATION");
        }
        byte[] complete = Files.readAllBytes(tar);
        byte[] markerBytes = "SECONDPAYLOAD_FOR_TRUNCATION".getBytes(StandardCharsets.UTF_8);
        int offset = -1;
        outer: for (int i = 0; i < complete.length - markerBytes.length; i++) {
            for (int j = 0; j < markerBytes.length; j++) {
                if (complete[i + j] != markerBytes[j]) continue outer;
            }
            offset = i;
            break;
        }
        assertThat(offset).isGreaterThan(0);
        Files.write(tar, Arrays.copyOf(complete, offset + 3));

        StreamArchiveImporter importer = createImporter();
        assertThatThrownBy(() -> {
            try (Stream<Book> stream = importer.importBooks(tar)) {
                stream.toList();
            }
        }).isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("TAR/CPIO");
    }

    private StreamArchiveImporter createImporter() throws Exception {
        return createImporter(com.myhomelibcorp.shared.archive.ArchiveSafetyLimits.MAX_ENTRY_BYTES,
                com.myhomelibcorp.shared.archive.ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES);
    }

    private StreamArchiveImporter createImporter(long maxEntryBytes, long maxTotalDecompressedBytes) throws Exception {
        BookImporterPort txt = new BookImporterPort() {
            @Override public boolean supports(Path file) {
                return file.toString().toLowerCase(Locale.ROOT).endsWith(".txt");
            }
            @Override public Stream<Book> importBooks(Path file) {
                try {
                    String title = Files.readString(file, StandardCharsets.UTF_8).trim();
                    if ("BAD".equals(title)) throw new IllegalArgumentException("malformed test book");
                    if ("PARTIAL".equals(title)) return Stream.of("Leaked", "BROKEN")
                            .map(value -> {
                                if ("BROKEN".equals(value)) throw new IllegalStateException("late parser failure");
                                return Book.builder().title(value).file(BookFile.empty()).build();
                            });
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

        StreamArchiveImporter importer = new StreamArchiveImporter(maxEntryBytes, maxTotalDecompressedBytes);
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
