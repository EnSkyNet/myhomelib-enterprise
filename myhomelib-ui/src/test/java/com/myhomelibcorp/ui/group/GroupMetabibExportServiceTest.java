package com.myhomelibcorp.ui.group;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.CollectionBookReadPort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GroupMetabibExportServiceTest {
    @TempDir Path temp;

    @Test
    void exportedGroupOpensAsJsonlPlusZipWithOnlyLocallyReadableBooks() throws Exception {
        Fixture f = fixture("collection-A");
        Book first = book("One", "first.fb2");
        Book second = book("Two", "second.fb2");
        Book missing = book("Missing", "missing.fb2");
        when(f.activeSession.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(first, missing, second), 3, 0, 100));
        when(f.resources.readBookData(first)).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream("first book".getBytes(StandardCharsets.UTF_8))));
        when(f.resources.readBookData(second)).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream("second book".getBytes(StandardCharsets.UTF_8))));
        when(f.resources.readBookData(missing)).thenReturn(Optional.empty());

        var result = f.exporter.export(21L, "collection-A", temp);
        assertThat(result.exported()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.folder()).exists();
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).containsExactly(result.folder());
        }

        List<String> records = Files.readAllLines(result.folder().resolve("catalog.jsonl"));
        assertThat(records).hasSize(3);
        ObjectMapper mapper = new ObjectMapper();
        var header = mapper.readTree(records.getFirst());
        assertThat(header.path("schema").asString()).isEqualTo("metabib.dataset/1");
        assertThat(header.path("records").asInt()).isEqualTo(2);
        assertThat(header.path("archives").get(0).path("entries").asInt()).isEqualTo(2);
        assertThat(header.path("archives").get(0).path("fb2_entries").asInt()).isEqualTo(2);
        assertThat(header.path("archives").get(0).path("collection_id").asString()).isEqualTo("collection-A");
        var one = mapper.readTree(records.get(1));
        var two = mapper.readTree(records.get(2));
        assertThat(one.path("record").path("library").asString()).isEqualTo("Primary");
        assertThat(one.path("record").path("locator").path("collection_id").asString()).isEqualTo("collection-A");
        assertThat(one.path("observations").size()).isEqualTo(2);
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
        verify(f.activeSession).findGroupById(21L);
        verify(f.activeSession, never()).findGroupByName(any());
    }

    @Test
    void sameNamedGroupsFromSeparateCollectionsAreExportedAsDistinctSources() throws Exception {
        Fixture f = fixture("collection-A");
        Collection secondCollection = mock(Collection.class);
        when(secondCollection.getId()).thenReturn("collection-B");
        when(secondCollection.getName()).thenReturn("Secondary");
        when(f.collectionRepository.findAll()).thenReturn(List.of(f.current, secondCollection));

        CollectionBookReadPort.Session secondSession = mock(CollectionBookReadPort.Session.class);
        when(f.reader.open(secondCollection)).thenReturn(secondSession);
        when(secondSession.findGroupByName("Shared group"))
                .thenReturn(Optional.of(new Group(GroupId.fromLong(4L), "shared GROUP", true)));

        Book first = book("From primary", "primary.fb2");
        Book second = book("From secondary", "secondary.fb2");
        when(f.activeSession.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(first), 1, 0, 100));
        when(secondSession.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(second), 1, 0, 100));
        when(f.resources.readBookData(first)).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream("primary".getBytes(StandardCharsets.UTF_8))));
        when(f.resources.readBookData(second)).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream("secondary".getBytes(StandardCharsets.UTF_8))));

        var result = f.exporter.export(21L, "collection-A", temp);
        assertThat(result.exported()).isEqualTo(2);
        assertThat(result.skipped()).isZero();

        List<String> lines = Files.readAllLines(result.folder().resolve("catalog.jsonl"));
        assertThat(lines).hasSize(3);
        ObjectMapper mapper = new ObjectMapper();
        var header = mapper.readTree(lines.getFirst());
        assertThat(header.path("archives")).hasSize(2);
        assertThat(header.path("archives").get(0).path("collection_id").asString()).isEqualTo("collection-A");
        assertThat(header.path("archives").get(1).path("collection_id").asString()).isEqualTo("collection-B");
        assertThat(mapper.readTree(lines.get(1)).path("record").path("library").asString()).isEqualTo("Primary");
        assertThat(mapper.readTree(lines.get(2)).path("record").path("library").asString()).isEqualTo("Secondary");
        assertThat(mapper.readTree(lines.get(2)).path("record").path("locator").path("collection_id").asString())
                .isEqualTo("collection-B");

        try (ZipFile primary = new ZipFile(result.folder().resolve("books-0001.zip").toFile());
             ZipFile secondary = new ZipFile(result.folder().resolve("books-0002.zip").toFile())) {
            assertThat(primary.size()).isEqualTo(1);
            assertThat(secondary.size()).isEqualTo(1);
            assertThat(new String(primary.getInputStream(primary.getEntry("book-000001.fb2")).readAllBytes(),
                    StandardCharsets.UTF_8)).isEqualTo("primary");
            assertThat(new String(secondary.getInputStream(secondary.getEntry("book-000002.fb2")).readAllBytes(),
                    StandardCharsets.UTF_8)).isEqualTo("secondary");
        }
        verify(secondSession).findGroupByName("Shared group");
    }

    @Test
    void unreadableSourceOpenIsSkippedWithoutDiscardingOtherValidBooks() throws Exception {
        Fixture f = fixture("partial");
        Book unreadable = book("Cannot open", "unreadable.fb2");
        Book valid = book("Valid", "valid.fb2");
        when(f.activeSession.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(unreadable, valid), 2, 0, 100));
        when(f.resources.readBookData(unreadable)).thenThrow(
                new UncheckedIOException("local file open failed", new IOException("permission denied")));
        when(f.resources.readBookData(valid)).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream("valid book".getBytes(StandardCharsets.UTF_8))));

        var result = f.exporter.export(32L, "partial", temp);
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
    void unavailableRegisteredCollectionFailsClosedInsteadOfSilentlyOmittingItsBooks() throws Exception {
        Fixture f = fixture("primary");
        Collection secondCollection = mock(Collection.class);
        when(secondCollection.getId()).thenReturn("unavailable");
        when(secondCollection.getName()).thenReturn("Unavailable");
        when(f.collectionRepository.findAll()).thenReturn(List.of(f.current, secondCollection));
        when(f.reader.open(secondCollection)).thenThrow(new IOException("database file is offline"));

        assertThatThrownBy(() -> f.exporter.export(7L, "primary", temp))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unavailable");
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void allFailedSourceOpensDoNotPublishEmptyGroupPackage() throws Exception {
        Fixture f = fixture("empty");
        Book unreadable = book("Unopenable", "noaccess.fb2");
        when(f.activeSession.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(unreadable), 1, 0, 100));
        when(f.resources.readBookData(unreadable)).thenThrow(
                new UncheckedIOException(new IOException("cannot open")));

        assertThatThrownBy(() -> f.exporter.export(42L, "empty", temp))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No locally readable books");
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void interruptedSourceOpenCancelsExportRatherThanSkippingBook() throws Exception {
        Fixture f = fixture("cancelled");
        Book unreadable = book("Interrupted", "interrupted.fb2");
        when(f.activeSession.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(unreadable), 1, 0, 100));
        when(f.resources.readBookData(unreadable)).thenThrow(
                new UncheckedIOException(new java.io.InterruptedIOException("cancelled by user")));

        assertThatThrownBy(() -> f.exporter.export(42L, "cancelled", temp))
                .isInstanceOf(IOException.class).hasMessageContaining("cancelled");
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void largeGroupUsesTitleKeysetContinuationAndCountsOnlyOnce() throws Exception {
        Fixture f = fixture("big");
        List<Book> first = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> book(String.format(java.util.Locale.ROOT, "Title %03d", i),
                        "b" + i + ".fb2")).toList();
        Book last = book("Title 100", "last.fb2");
        when(f.activeSession.findPage(any(BookQuery.class)))
                .thenReturn(PageResult.of(first, 101, 0, 100));
        BookPageCursor expectedCursor = new BookPageCursor("Title 099", first.getLast().getId().asString());
        when(f.activeSession.findTitlePageByCursor(any(BookQuery.class), eq(expectedCursor),
                eq(BookPageDirection.AFTER), eq(101L)))
                .thenReturn(PageResult.of(List.of(last), 101, 1, 100));
        when(f.resources.readBookData(any(Book.class))).thenAnswer(inv ->
                Optional.of(new ByteArrayInputStream(new byte[]{42})));

        var exported = f.exporter.export(99L, "big", temp);
        assertThat(exported.exported()).isEqualTo(101);
        assertThat(exported.skipped()).isZero();
        verify(f.activeSession, times(1)).findPage(any(BookQuery.class));
        verify(f.activeSession, times(1)).findTitlePageByCursor(any(BookQuery.class),
                any(BookPageCursor.class), eq(BookPageDirection.AFTER), eq(101L));
        try (ZipFile zip = new ZipFile(exported.folder().resolve("books-0001.zip").toFile())) {
            assertThat(zip.size()).isEqualTo(101);
        }
    }

    @Test
    void archiveBoundaryAtOneThousandEntriesProducesTwoImportableZipBatches() throws Exception {
        Fixture f = fixture("batch");
        List<Book> items = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> book(String.format(java.util.Locale.ROOT, "Book %04d", i),
                        "book" + i + ".fb2")).toList();
        when(f.activeSession.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(items.subList(0, 100), 1001, 0, 100));
        when(f.activeSession.findTitlePageByCursor(any(BookQuery.class), any(BookPageCursor.class),
                eq(BookPageDirection.AFTER), eq(1001L))).thenAnswer(inv -> {
            BookPageCursor cursor = inv.getArgument(1);
            int lastIndex = java.util.stream.IntStream.range(0, items.size())
                    .filter(i -> items.get(i).getId().asString().equals(cursor.id()))
                    .findFirst().orElseThrow();
            int start = lastIndex + 1;
            return PageResult.of(items.subList(start, Math.min(start + 100, items.size())),
                    1001, start / 100, 100);
        });
        when(f.resources.readBookData(any(Book.class)))
                .thenAnswer(inv -> Optional.of(new ByteArrayInputStream(new byte[]{65})));

        var result = f.exporter.export(15L, "batch", temp);
        assertThat(result.exported()).isEqualTo(1001);
        assertThat(result.skipped()).isZero();
        verify(f.activeSession, times(1)).findPage(any(BookQuery.class));
        verify(f.activeSession, times(10)).findTitlePageByCursor(any(BookQuery.class),
                any(BookPageCursor.class), eq(BookPageDirection.AFTER), eq(1001L));
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
    void relativeLegacyPathWithoutAnyTrustedCollectionRootIsSkipped() throws Exception {
        Fixture f = fixture("no-root");
        Book book = book("No trusted root", "relative.fb2");
        when(book.getCollectionRoot()).thenReturn("");
        when(f.activeSession.findPage(any(BookQuery.class)))
                .thenReturn(PageResult.of(List.of(book), 1, 0, 100));

        assertThatThrownBy(() -> f.exporter.export(19L, "no-root", temp))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No locally readable books");
        verifyNoInteractions(f.resources);
    }

    @Test
    void legacyRelativeBookPathUsesTheRegisteredSourceCollectionRoot() throws Exception {
        Fixture f = fixture("rooted");
        Path collectionRoot = temp.resolve("source-collection-root");
        when(f.current.getRootFolder()).thenReturn(collectionRoot);
        Book book = book("Legacy relative path", "legacy.fb2");
        when(book.getFolder()).thenReturn("");
        when(book.getArchiveEntry()).thenReturn("");
        when(book.getCollectionRoot()).thenReturn("");
        when(f.activeSession.findPage(any(BookQuery.class)))
                .thenReturn(PageResult.of(List.of(book), 1, 0, 100));
        when(f.resources.readBookData(eq("legacy.fb2"), eq(""), eq(collectionRoot.toString()), eq("")))
                .thenReturn(Optional.of(new ByteArrayInputStream(
                        "legacy bytes".getBytes(StandardCharsets.UTF_8))));

        var result = f.exporter.export(17L, "rooted", temp);

        assertThat(result.exported()).isEqualTo(1);
        verify(f.resources).readBookData("legacy.fb2", "", collectionRoot.toString(), "");
    }

    @Test
    void interruptedFileCopyCannotPublishPartialPortableCollection() throws Exception {
        Fixture f = fixture("A");
        Book book = book("Failing", "broken.fb2");
        when(f.activeSession.findPage(any(BookQuery.class))).thenReturn(PageResult.of(List.of(book), 1, 0, 100));
        when(f.resources.readBookData(book)).thenReturn(Optional.of(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("source unreadable"); }
        }));
        assertThatThrownBy(() -> f.exporter.export(7L, "A", temp))
                .isInstanceOf(IOException.class).hasMessageContaining("source unreadable");
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void activeCollectionChangeAfterLastBookPreventsPackagePublication() throws Exception {
        Fixture f = fixture("A");
        Collection changed = mock(Collection.class);
        when(changed.getId()).thenReturn("B");
        when(f.lifecycle.getCurrentCollection()).thenReturn(
                f.current, f.current, f.current, f.current, changed);
        Book book = book("Final book", "final.fb2");
        when(f.activeSession.findPage(any(BookQuery.class)))
                .thenReturn(PageResult.of(List.of(book), 1, 0, 100));
        when(f.resources.readBookData(book)).thenReturn(Optional.of(
                new ByteArrayInputStream("complete".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> f.exporter.export(7L, "A", temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Collection changed during group export");
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void activeCollectionChangeDuringBookIterationIsNotReportedAsGroupAmbiguity() throws Exception {
        Fixture f = fixture("A");
        Collection changed = mock(Collection.class);
        when(changed.getId()).thenReturn("B");
        when(f.lifecycle.getCurrentCollection()).thenReturn(f.current, f.current, f.current, changed);
        Book book = book("Changing collection", "changing.fb2");
        when(f.activeSession.findPage(any(BookQuery.class)))
                .thenReturn(PageResult.of(List.of(book), 1, 0, 100));

        assertThatThrownBy(() -> f.exporter.export(7L, "A", temp))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Collection changed during group export")
                .hasMessageNotContaining("unambiguously");
        verifyNoInteractions(f.resources);
    }

    @Test
    void rejectingAChangedCollectionDoesNotCreateDestination() {
        Fixture f = fixture("old");
        assertThatThrownBy(() -> f.exporter.export(5L, "new", temp))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Collection changed");
        verifyNoInteractions(f.resources, f.reader, f.collectionRepository);
    }

    private Fixture fixture(String collectionId) {
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        CollectionRepository collectionRepository = mock(CollectionRepository.class);
        CollectionBookReadPort reader = mock(CollectionBookReadPort.class);
        CollectionBookReadPort.Session activeSession = mock(CollectionBookReadPort.Session.class);
        Collection current = mock(Collection.class);
        when(current.getId()).thenReturn(collectionId);
        when(current.getName()).thenReturn("Primary");
        when(lifecycle.getCurrentCollection()).thenReturn(current);
        when(collectionRepository.findAll()).thenReturn(List.of(current));
        when(reader.open(current)).thenReturn(activeSession);
        when(activeSession.findGroupById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.of(new Group(GroupId.fromLong(id), "Shared group", false));
        });
        return new Fixture(reader, collectionRepository, resources, lifecycle, current, activeSession,
                new GroupMetabibExportService(reader, collectionRepository, resources, lifecycle));
    }

    private static Book book(String title, String fileName) {
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(BookId.generate());
        when(book.getTitle()).thenReturn(title);
        when(book.getFileName()).thenReturn(fileName);
        when(book.getCollectionRoot()).thenReturn("/fixture-root");
        when(book.getFolder()).thenReturn("");
        when(book.getArchiveEntry()).thenReturn("");
        when(book.getAuthors()).thenReturn(List.of());
        when(book.getGenres()).thenReturn(List.of());
        return book;
    }

    private record Fixture(CollectionBookReadPort reader,
                           CollectionRepository collectionRepository,
                           BookResourcePort resources,
                           CollectionLifecyclePort lifecycle,
                           Collection current,
                           CollectionBookReadPort.Session activeSession,
                           GroupMetabibExportService exporter) { }
}
