package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.application.comic.duplicates.ComicDuplicateScanControl;
import com.myhomelibcorp.application.comic.duplicates.ScanComicDuplicatePagesUseCase;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in V34 archive-level exact-duplicate performance probe. */
class RealComicDuplicatePerformanceProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.comic", matches = ".+")
    void scansLargeComicArchiveThroughProductionArchiveReader() throws Exception {
        Path archive = Path.of(System.getProperty("mhl.real.comic")).toAbsolutePath().normalize();
        assertThat(archive).isRegularFile();

        BookId id = BookId.fromString("00000000-0000-0000-0000-00000000c034");
        Book book = Book.builder().id(id).title("V34 real archive probe")
                .file(new BookFile(archive.toString(), "", "", Files.size(archive), "")).build();
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(books.findById(id)).thenReturn(Optional.of(book));

        var resources = new BookResourceResolver(new ZipArchiveReader());
        var scanner = new ScanComicDuplicatePagesUseCase(books, resources);
        long started = System.nanoTime();
        var result = scanner.scan(id, ComicDuplicateScanControl.none());
        long wallMs = (System.nanoTime() - started) / 1_000_000L;

        System.out.printf(java.util.Locale.ROOT,
                "REAL_V34_COMIC archive=%s archiveBytes=%d totalPages=%d scannedPages=%d groups=%d failures=%d wallMs=%d%n",
                archive.getFileName(), Files.size(archive), result.totalPages(), result.scannedPages(),
                result.groups().size(), result.failures().size(), wallMs);
        assertThat(result.totalPages()).isGreaterThanOrEqualTo(1000);
        assertThat(result.scannedPages()).isEqualTo(result.totalPages());
        assertThat(result.failures()).isEmpty();
        assertThat(result.groups()).isNotEmpty();
    }
}
