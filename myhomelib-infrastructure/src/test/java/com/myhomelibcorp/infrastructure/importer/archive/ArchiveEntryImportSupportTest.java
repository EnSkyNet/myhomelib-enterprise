package com.myhomelibcorp.infrastructure.importer.archive;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveEntryImportSupportTest {

    @Test
    void materializesImportsEnrichesAndDeletesTemporaryEntry(@TempDir Path dir) throws Exception {
        byte[] content = "book-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AtomicReference<Path> materialized = new AtomicReference<>();
        BookImporterPort importer = importer(path -> {
            materialized.set(path);
            try {
                assertThat(Files.readAllBytes(path)).isEqualTo(content);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return Stream.of(Book.builder().title("Imported").file(BookFile.empty()).build());
        });
        Queue<Book> destination = new ArrayDeque<>();
        int[] offset = {0};

        long total = ArchiveEntryImportSupport.materializeAndImport(
                dir.resolve("library.7z"),
                "books/Книга.fb2",
                importer,
                buffer -> {
                    if (offset[0] >= content.length) return -1;
                    int length = Math.min(buffer.length, content.length - offset[0]);
                    System.arraycopy(content, offset[0], buffer, 0, length);
                    offset[0] += length;
                    return length;
                },
                7,
                destination);

        assertThat(total).isEqualTo(7 + content.length);
        assertThat(destination).hasSize(1);
        Book imported = destination.remove();
        assertThat(imported.getFile().getFileName()).isEqualTo("Книга.fb2");
        assertThat(imported.getFile().getArchiveEntry()).isEqualTo("books/Книга.fb2");
        assertThat(imported.getFile().getFileSize()).isEqualTo(content.length);
        assertThat(materialized.get()).isNotNull();
        assertThat(materialized.get()).doesNotExist();
    }

    @Test
    void deletesTemporaryEntryWhenImporterFails(@TempDir Path dir) {
        AtomicReference<Path> materialized = new AtomicReference<>();
        BookImporterPort importer = importer(path -> {
            materialized.set(path);
            throw new IllegalStateException("broken importer");
        });

        assertThatThrownBy(() -> ArchiveEntryImportSupport.materializeAndImport(
                dir.resolve("library.rar"),
                "book.fb2",
                importer,
                new SingleChunkReader(new byte[]{1, 2, 3}),
                0,
                new ArrayDeque<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broken importer");

        assertThat(materialized.get()).isNotNull();
        assertThat(materialized.get()).doesNotExist();
    }

    @Test
    void rejectsZeroProgressReaderInsteadOfSpinning(@TempDir Path dir) {
        BookImporterPort importer = importer(path -> Stream.empty());

        assertThatThrownBy(() -> ArchiveEntryImportSupport.materializeAndImport(
                dir.resolve("library.7z"),
                "book.fb2",
                importer,
                buffer -> 0,
                0,
                new ArrayDeque<>()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no progress");
    }

    @Test
    void cumulativeLimitUsesOverflowSafeArithmetic() {
        assertThatThrownBy(() -> ArchiveEntryImportSupport.addToCumulativeTotal(
                ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES, 1))
                .isInstanceOf(ArchiveEntryImportSupport.CumulativeArchiveLimitException.class);

        assertThatThrownBy(() -> ArchiveEntryImportSupport.addToCumulativeTotal(Long.MAX_VALUE, Long.MAX_VALUE))
                .isInstanceOf(ArchiveEntryImportSupport.CumulativeArchiveLimitException.class);
    }

    private static BookImporterPort importer(ImportAction action) {
        return new BookImporterPort() {
            @Override public boolean supports(Path file) { return true; }
            @Override public Stream<Book> importBooks(Path file) { return action.importBooks(file); }
            @Override public String getFormatName() { return "TEST"; }
            @Override public long countBooks(Path file) { return 1; }
        };
    }

    @FunctionalInterface
    private interface ImportAction {
        Stream<Book> importBooks(Path path);
    }

    private static final class SingleChunkReader implements ArchiveEntryImportSupport.EntryReader {
        private final byte[] bytes;
        private boolean read;

        private SingleChunkReader(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            if (read) return -1;
            read = true;
            System.arraycopy(bytes, 0, buffer, 0, bytes.length);
            return bytes.length;
        }
    }
}
