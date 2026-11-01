package com.myhomelibcorp.ui.group;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GroupMetabibExportServiceTest {
    @TempDir Path temp;

    @Test
    void exportedGroupOpensAsJsonlPlusZipWithOnlyLocallyReadableBooks() throws Exception {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection current = mock(Collection.class);
        when(current.getId()).thenReturn("collection-A");
        when(collections.getCurrentCollection()).thenReturn(current);

        Book first = book("One", "first.fb2");
        Book second = book("Two", "second.fb2");
        Book missing = book("Missing", "missing.fb2");
        when(repository.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(first, missing, second), 3, 0, 100));
        when(resources.readBookData(first)).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream("first book".getBytes(StandardCharsets.UTF_8))));
        when(resources.readBookData(second)).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream("second book".getBytes(StandardCharsets.UTF_8))));
        when(resources.readBookData(missing)).thenReturn(Optional.empty());

        var result = new GroupMetabibExportService(repository, resources, collections)
                .export(21L, "collection-A", temp);
        assertThat(result.exported()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.folder()).exists();
        assertThat(Files.list(temp).toList()).containsExactly(result.folder());
        List<String> records = Files.readAllLines(result.folder().resolve("catalog.jsonl"));
        assertThat(records).hasSize(3);
        ObjectMapper mapper = new ObjectMapper();
        var header = mapper.readTree(records.getFirst());
        assertThat(header.path("schema").asString()).isEqualTo("metabib.dataset/1");
        assertThat(header.path("records").asInt()).isEqualTo(2);
        assertThat(header.path("archives").get(0).path("entries").asInt()).isEqualTo(2);
        assertThat(header.path("archives").get(0).path("fb2_entries").asInt()).isEqualTo(2);
        var one = mapper.readTree(records.get(1));
        var two = mapper.readTree(records.get(2));
        assertThat(one.path("record").path("locator").path("index").asInt()).isEqualTo(0);
        assertThat(two.path("record").path("locator").path("index").asInt()).isEqualTo(1);
        assertThat(one.path("claims").path("bibliographic").path("title").get(0).path("value").asString()).isEqualTo("One");
        assertThat(two.path("claims").path("bibliographic").path("title").get(0).path("value").asString()).isEqualTo("Two");
        try (ZipFile zip = new ZipFile(result.folder().resolve("books-0001.zip").toFile())) {
            assertThat(zip.size()).isEqualTo(2);
            assertThat(new String(zip.getInputStream(zip.getEntry("book-000001.fb2")).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("first book");
            assertThat(new String(zip.getInputStream(zip.getEntry("book-000002.fb2")).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("second book");
        }
    }

    @Test
    void unreadableSourceOpenIsSkippedWithoutDiscardingOtherValidBooks() throws Exception {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection current = mock(Collection.class);
        when(current.getId()).thenReturn("partial");
        when(collections.getCurrentCollection()).thenReturn(current);

        Book unreadable = book("Cannot open", "unreadable.fb2");
        Book valid = book("Valid", "valid.fb2");
        when(repository.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(unreadable, valid), 2, 0, 100));
        when(resources.readBookData(unreadable)).thenThrow(
                new UncheckedIOException("local file open failed", new IOException("permission denied")));
        when(resources.readBookData(valid)).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream("valid book".getBytes(StandardCharsets.UTF_8))));

        var result = new GroupMetabibExportService(repository, resources, collections)
                .export(32L, "partial", temp);
        assertThat(result.exported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        var records = Files.readAllLines(result.folder().resolve("catalog.jsonl"));
        assertThat(records).hasSize(2);
        var mapper = new ObjectMapper();
        assertThat(mapper.readTree(records.getFirst()).path("records").asInt()).isEqualTo(1);
        assertThat(mapper.readTree(records.get(1)).path("claims").path("bibliographic")
                .path("title").get(0).path("value").asString()).isEqualTo("Valid");
        try (ZipFile zip = new ZipFile(result.folder().resolve("books-0001.zip").toFile())) {
            assertThat(zip.size()).isEqualTo(1);
            assertThat(zip.getEntry("book-000001.fb2")).isNotNull();
        }
    }

    @Test
    void allFailedSourceOpensDoNotPublishEmptyGroupPackage() throws Exception {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection current = mock(Collection.class);
        when(current.getId()).thenReturn("empty");
        when(collections.getCurrentCollection()).thenReturn(current);
        Book unreadable = book("Unopenable", "noaccess.fb2");
        when(repository.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(unreadable), 1, 0, 100));
        when(resources.readBookData(unreadable)).thenThrow(
                new UncheckedIOException(new IOException("cannot open")));

        var exporter = new GroupMetabibExportService(repository, resources, collections);
        assertThatThrownBy(() -> exporter.export(42L, "empty", temp))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No locally readable books");
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void interruptedSourceOpenCancelsExportRatherThanSkippingBook() throws Exception {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection current = mock(Collection.class);
        when(current.getId()).thenReturn("cancelled");
        when(collections.getCurrentCollection()).thenReturn(current);
        Book unreadable = book("Interrupted", "interrupted.fb2");
        when(repository.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(unreadable), 1, 0, 100));
        when(resources.readBookData(unreadable)).thenThrow(
                new UncheckedIOException(new java.io.InterruptedIOException("cancelled by user")));

        var exporter = new GroupMetabibExportService(repository, resources, collections);
        assertThatThrownBy(() -> exporter.export(42L, "cancelled", temp))
                .isInstanceOf(IOException.class).hasMessageContaining("cancelled");
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void largeGroupUsesTitleKeysetContinuationAndCountsOnlyOnce() throws Exception {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection current = mock(Collection.class);
        when(current.getId()).thenReturn("big");
        when(collections.getCurrentCollection()).thenReturn(current);

        List<Book> first = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> book(String.format(java.util.Locale.ROOT, "Title %03d", i),
                        "b" + i + ".fb2")).toList();
        Book last = book("Title 100", "last.fb2");
        when(repository.findPage(any(BookQuery.class)))
                .thenReturn(PageResult.of(first, 101, 0, 100));
        // Evaluate the mocked book ID before registering Mockito argument matchers.
        BookPageCursor expectedCursor = new BookPageCursor("Title 099", first.getLast().getId().asString());
        when(repository.findTitlePageByCursor(any(BookQuery.class), eq(expectedCursor),
                eq(BookPageDirection.AFTER), eq(101L)))
                .thenReturn(PageResult.of(List.of(last), 101, 1, 100));
        when(resources.readBookData(any(Book.class))).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream(new byte[]{42})));

        var exported = new GroupMetabibExportService(repository, resources, collections)
                .export(99L, "big", temp);
        assertThat(exported.exported()).isEqualTo(101);
        assertThat(exported.skipped()).isZero();
        verify(repository, times(1)).findPage(any(BookQuery.class));
        verify(repository, times(1)).findTitlePageByCursor(any(BookQuery.class),
                any(BookPageCursor.class), eq(BookPageDirection.AFTER), eq(101L));
        verify(repository, never()).findPage(any(BookQuery.class), anyLong());
        try (ZipFile zip = new ZipFile(exported.folder().resolve("books-0001.zip").toFile())) {
            assertThat(zip.size()).isEqualTo(101);
        }
    }

    @Test
    void archiveBoundaryAtOneThousandEntriesProducesTwoImportableZipBatches() throws Exception {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection current = mock(Collection.class);
        when(current.getId()).thenReturn("batch");
        when(collections.getCurrentCollection()).thenReturn(current);

        List<Book> items = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> book(String.format(java.util.Locale.ROOT, "Book %04d", i),
                        "book" + i + ".fb2")).toList();
        when(repository.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(items.subList(0, 100), 1001, 0, 100));
        when(repository.findTitlePageByCursor(any(BookQuery.class), any(BookPageCursor.class),
                eq(BookPageDirection.AFTER), eq(1001L))).thenAnswer(inv -> {
            BookPageCursor cursor = inv.getArgument(1);
            int lastIndex = java.util.stream.IntStream.range(0, items.size())
                    .filter(i -> items.get(i).getId().asString().equals(cursor.id()))
                    .findFirst().orElseThrow();
            int start = lastIndex + 1;
            return PageResult.of(items.subList(start, Math.min(start + 100, items.size())),
                    1001, start / 100, 100);
        });
        when(resources.readBookData(any(Book.class)))
                .thenAnswer(inv -> Optional.of(new ByteArrayInputStream(new byte[]{65})));

        var result = new GroupMetabibExportService(repository, resources, collections)
                .export(15L, "batch", temp);
        assertThat(result.exported()).isEqualTo(1001);
        assertThat(result.skipped()).isZero();
        verify(repository, times(1)).findPage(any(BookQuery.class));
        verify(repository, times(10)).findTitlePageByCursor(any(BookQuery.class),
                any(BookPageCursor.class), eq(BookPageDirection.AFTER), eq(1001L));
        verify(repository, never()).findPage(any(BookQuery.class), anyLong());
        try (ZipFile first = new ZipFile(result.folder().resolve("books-0001.zip").toFile());
             ZipFile second = new ZipFile(result.folder().resolve("books-0002.zip").toFile())) {
            assertThat(first.size()).isEqualTo(1000);
            assertThat(second.size()).isEqualTo(1);
            assertThat(second.getEntry("book-001001.fb2")).isNotNull();
        }
        var lines = Files.readAllLines(result.folder().resolve("catalog.jsonl"));
        assertThat(lines).hasSize(1002);
        var header = new ObjectMapper().readTree(lines.getFirst());
        assertThat(header.path("archives").size()).isEqualTo(2);
        assertThat(header.path("archives").get(0).path("entries").asInt()).isEqualTo(1000);
        assertThat(header.path("archives").get(1).path("entries").asInt()).isEqualTo(1);
    }

    @Test
    void interruptedFileCopyCannotPublishPartialPortableCollection() throws Exception {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection current = mock(Collection.class);
        when(current.getId()).thenReturn("A");
        when(collections.getCurrentCollection()).thenReturn(current);
        Book book = book("Failing", "broken.fb2");
        when(repository.findPage(any(BookQuery.class))).thenReturn(PageResult.of(List.of(book), 1, 0, 100));
        when(resources.readBookData(book)).thenReturn(Optional.of(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("source unreadable"); }
        }));
        var exporter = new GroupMetabibExportService(repository, resources, collections);
        assertThatThrownBy(() -> exporter.export(7L, "A", temp))
                .isInstanceOf(IOException.class).hasMessageContaining("source unreadable");
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void rejectingAChangedCollectionDoesNotCreateDestination() {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection current = mock(Collection.class);
        when(current.getId()).thenReturn("old");
        when(collections.getCurrentCollection()).thenReturn(current);
        var exporter = new GroupMetabibExportService(repository, resources, collections);
        assertThatThrownBy(() -> exporter.export(5L, "new", temp))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Collection changed");
        verifyNoInteractions(resources, repository);
    }

    private static Book book(String title, String fileName) {
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(BookId.generate());
        when(book.getTitle()).thenReturn(title);
        when(book.getFileName()).thenReturn(fileName);
        when(book.getAuthors()).thenReturn(List.of());
        when(book.getGenres()).thenReturn(List.of());
        return book;
    }
}
