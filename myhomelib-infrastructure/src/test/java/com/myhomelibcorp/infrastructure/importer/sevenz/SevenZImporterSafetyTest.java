package com.myhomelibcorp.infrastructure.importer.sevenz;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SevenZImporterSafetyTest {

    @Test
    void rejectsSevenZThroughSymlinkedParentBeforeArchiveOpen(@TempDir Path dir) throws Exception {
        Path realDir = Files.createDirectory(dir.resolve("real-seven"));
        Files.writeString(realDir.resolve("books.7z"), "not-even-a-seven-z");
        Path alias = dir.resolve("alias-seven");
        Files.createSymbolicLink(alias, realDir.getFileName());

        SevenZImporter importer = new SevenZImporter();

        assertThatThrownBy(() -> importer.importBooks(alias.resolve("books.7z")))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(IOException.class);
    }

    @Test
    void ignoredEntryIsDrainedBeforeLaterBookAndCountUsesMetadata(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("mixed.7z");
        try (SevenZOutputFile out = new SevenZOutputFile(archive.toFile())) {
            put7z(out, "notes.bin", "ignored-payload".getBytes(StandardCharsets.UTF_8));
            put7z(out, "good.txt", "Good".getBytes(StandardCharsets.UTF_8));
        }

        SevenZImporter importer = createImporter();
        assertThat(importer.countBooks(archive)).isEqualTo(1);
        try (Stream<Book> books = importer.importBooks(archive)) {
            assertThat(books.toList()).extracting(Book::getTitle).containsExactly("Good");
        }
    }

    @Test
    void skippedEntryCannotBypassCumulativeDecompressionBudget(@TempDir Path dir) throws Exception {
        SevenZImporter importer = new SevenZImporter();
        ImporterRegistry registry = mock(ImporterRegistry.class);
        when(registry.findImporter(any(Path.class))).thenThrow(new IllegalArgumentException("unsupported"));
        Field registryField = SevenZImporter.class.getDeclaredField("importerRegistry");
        registryField.setAccessible(true);
        registryField.set(importer, registry);

        SevenZFile archive = mock(SevenZFile.class);
        SevenZArchiveEntry skipped = new SevenZArchiveEntry();
        skipped.setName("notes.bin");
        skipped.setSize(3);
        when(archive.getNextEntry()).thenReturn(skipped);
        when(archive.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(invocation -> {
            byte[] buffer = invocation.getArgument(0);
            int offset = invocation.getArgument(1);
            buffer[offset] = 'a';
            buffer[offset + 1] = 'b';
            buffer[offset + 2] = 'c';
            return 3;
        });

        FileChannel channel = mock(FileChannel.class);
        Class<?> handleType = Class.forName(SevenZImporter.class.getName() + "$SevenZHandle");
        Constructor<?> handleConstructor = handleType.getDeclaredConstructor(FileChannel.class, SevenZFile.class);
        handleConstructor.setAccessible(true);
        Object handle = handleConstructor.newInstance(channel, archive);
        Class<?> iteratorType = Class.forName(SevenZImporter.class.getName() + "$SevenZIterator");
        Constructor<?> iteratorConstructor = iteratorType.getDeclaredConstructor(SevenZImporter.class, Path.class, handleType);
        iteratorConstructor.setAccessible(true);
        Iterator<?> iterator = (Iterator<?>) iteratorConstructor.newInstance(importer, dir.resolve("budget.7z"), handle);
        Field totalBytes = iteratorType.getDeclaredField("totalDecompressedBytes");
        totalBytes.setAccessible(true);
        totalBytes.setLong(iterator, ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES - 2);

        assertThatThrownBy(iterator::hasNext)
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("Archive exceeds cumulative decompression safety limit");
        verify(archive, times(1)).read(any(byte[].class), anyInt(), anyInt());
        verify(archive).close();
        verify(channel).close();
    }

    private SevenZImporter createImporter() throws Exception {
        BookImporterPort txt = new BookImporterPort() {
            @Override public boolean supports(Path file) {
                return file.toString().toLowerCase(Locale.ROOT).endsWith(".txt");
            }
            @Override public Stream<Book> importBooks(Path file) {
                try {
                    String title = Files.readString(file, StandardCharsets.UTF_8).trim();
                    return Stream.of(Book.builder().title(title).file(BookFile.empty()).build());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
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
        SevenZImporter importer = new SevenZImporter();
        Field field = SevenZImporter.class.getDeclaredField("importerRegistry");
        field.setAccessible(true);
        field.set(importer, registry);
        return importer;
    }

    private static void put7z(SevenZOutputFile out, String name, byte[] payload) throws Exception {
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(name);
        entry.setSize(payload.length);
        out.putArchiveEntry(entry);
        out.write(payload);
        out.closeArchiveEntry();
    }
}
