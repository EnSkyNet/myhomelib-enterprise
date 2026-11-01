package com.myhomelibcorp.e2e;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.CollectionBookReadPort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.Isbn;
import com.myhomelibcorp.infrastructure.catalog.reader.MetabibCatalogReader;
import com.myhomelibcorp.ui.group.GroupMetabibExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Complete cross-collection format compatibility check: records from two collection
 * databases must pass the real metabib.dataset/1 reader after the package is relocated.
 */
class GroupMetabibExportRoundTripE2ETest {
    @TempDir Path root;

    @Test
    void exportedPortableGroupFromTwoCollectionsCanBeImportedByEnterpriseMetabibReader() throws Exception {
        CollectionBookReadPort reader = mock(CollectionBookReadPort.class);
        CollectionRepository collectionRepository = mock(CollectionRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);

        Collection primary = mock(Collection.class);
        when(primary.getId()).thenReturn("source-collection-a");
        when(primary.getName()).thenReturn("Source A");
        when(collections.getCurrentCollection()).thenReturn(primary);

        Collection secondary = mock(Collection.class);
        when(secondary.getId()).thenReturn("source-collection-b");
        when(secondary.getName()).thenReturn("Source B");
        when(collectionRepository.findAll()).thenReturn(List.of(primary, secondary));

        CollectionBookReadPort.Session primarySession = mock(CollectionBookReadPort.Session.class);
        CollectionBookReadPort.Session secondarySession = mock(CollectionBookReadPort.Session.class);
        when(reader.open(primary)).thenReturn(primarySession);
        when(reader.open(secondary)).thenReturn(secondarySession);
        when(primarySession.findGroupById(37L)).thenReturn(
                Optional.of(new Group(GroupId.fromLong(37L), "Shared export group", false)));
        when(secondarySession.findGroupByName("Shared export group")).thenReturn(
                Optional.of(new Group(GroupId.fromLong(9L), "shared EXPORT group", false)));

        Book first = book("Portable metabib sample", "sample.fb2", "893718");
        when(first.getFileSize()).thenReturn(400000L); // intentionally stale; ZIP entry is smaller
        when(first.getIsbn()).thenReturn(Isbn.of("9780306406157"));
        when(first.getPublisher()).thenReturn("Книжкова фабрика");
        when(first.getCity()).thenReturn("Київ");
        when(first.getKeywords()).thenReturn("наука, фантастика");
        when(first.getTranslators()).thenReturn("А. Б. Перекладач");
        Book second = book("Second collection sample", "second.fb2", "999001");

        when(primarySession.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(first), 1, 0, 100));
        when(secondarySession.findPage(any(BookQuery.class))).thenReturn(
                PageResult.of(List.of(second), 1, 0, 100));
        when(resources.readBookData(first)).thenReturn(Optional.of(
                new ByteArrayInputStream("<FictionBook/>".getBytes(StandardCharsets.UTF_8))));
        when(resources.readBookData(second)).thenReturn(Optional.of(
                new ByteArrayInputStream("<FictionBook><description/></FictionBook>".getBytes(StandardCharsets.UTF_8))));

        var exported = new GroupMetabibExportService(reader, collectionRepository, resources, collections)
                .export(37L, "source-collection-a", root);

        // Move the complete package: all references must stay relative to the
        // destination directory rather than to the original export location.
        Path relocated = root.resolve("relocated-group");
        Files.move(exported.folder(), relocated);
        Path jsonl = relocated.resolve("catalog.jsonl");
        try (var imported = new MetabibCatalogReader(new ObjectMapper()).open(jsonl)) {
            assertThat(imported.dataset().schema()).isEqualTo("metabib.dataset/1");
            assertThat(imported.dataset().declaredRecords()).isEqualTo(2L);

            assertThat(imported.hasNext()).isTrue();
            var firstRecord = imported.next();
            assertThat(firstRecord.title()).isEqualTo("Portable metabib sample");
            assertThat(firstRecord.size()).isEqualTo((long) "<FictionBook/>".getBytes(StandardCharsets.UTF_8).length);
            assertThat(firstRecord.isbn()).isEqualTo("9780306406157");
            assertThat(firstRecord.publisher()).isEqualTo("Книжкова фабрика");
            assertThat(firstRecord.publicationCity()).isEqualTo("Київ");
            assertThat(firstRecord.keywords()).containsExactly("наука", "фантастика");
            assertThat(firstRecord.translators()).hasSize(1);
            assertThat(firstRecord.translators().getFirst().displayName()).isEqualTo("А. Б. Перекладач");
            assertThat(firstRecord.sourceBookId()).contains("Source A", "zip-0001");
            assertThat(firstRecord.archive()).isEqualTo("books-0001.zip");
            assertThat(firstRecord.archiveEntry()).isEqualTo("book-000001.fb2");
            assertThat(relocated.resolve(firstRecord.archive())).isRegularFile();
            assertThat(firstRecord.sourceMetadata()).containsEntry("locator.index", "0")
                    .containsEntry("locator.source", "zip-0001");
            assertThat(firstRecord.externalIdentities()).anySatisfy(identity -> {
                assertThat(identity.scheme()).isEqualTo("catalog:mhl-source-libid");
                assertThat(identity.value()).isEqualTo("893718");
            });

            assertThat(imported.hasNext()).isTrue();
            var secondRecord = imported.next();
            assertThat(secondRecord.title()).isEqualTo("Second collection sample");
            assertThat(secondRecord.size()).isEqualTo((long)
                    "<FictionBook><description/></FictionBook>".getBytes(StandardCharsets.UTF_8).length);
            assertThat(secondRecord.sourceBookId()).contains("Source B", "zip-0002");
            assertThat(secondRecord.archive()).isEqualTo("books-0002.zip");
            assertThat(secondRecord.archiveEntry()).isEqualTo("book-000002.fb2");
            assertThat(secondRecord.sourceMetadata()).containsEntry("locator.index", "0")
                    .containsEntry("locator.source", "zip-0002");
            assertThat(secondRecord.externalIdentities()).anySatisfy(identity -> {
                assertThat(identity.scheme()).isEqualTo("catalog:mhl-source-libid");
                assertThat(identity.value()).isEqualTo("999001");
            });

            try (ZipFile primaryZip = new ZipFile(relocated.resolve(firstRecord.archive()).toFile());
                 ZipFile secondaryZip = new ZipFile(relocated.resolve(secondRecord.archive()).toFile())) {
                assertThat(primaryZip.getEntry(firstRecord.archiveEntry())).isNotNull();
                assertThat(secondaryZip.getEntry(secondRecord.archiveEntry())).isNotNull();
            }
            assertThat(imported.hasNext()).isFalse();
        }
    }

    private static Book book(String title, String fileName, String libId) {
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(BookId.generate());
        when(book.getTitle()).thenReturn(title);
        when(book.getFileName()).thenReturn(fileName);
        when(book.getAuthors()).thenReturn(List.of());
        when(book.getGenres()).thenReturn(List.of());
        when(book.getLibId()).thenReturn(libId);
        return book;
    }
}
