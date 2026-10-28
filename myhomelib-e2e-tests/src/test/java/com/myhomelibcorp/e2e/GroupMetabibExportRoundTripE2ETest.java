package com.myhomelibcorp.e2e;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.catalog.reader.MetabibCatalogReader;
import com.myhomelibcorp.ui.group.GroupMetabibExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Complete format compatibility check: exported JSONL must pass the actual
 * metabib.dataset/1 reader and reconstruct its archive locator/title/identity.
 */
class GroupMetabibExportRoundTripE2ETest {
    @TempDir Path root;

    @Test
    void exportedPortableGroupCanBeImportedByEnterpriseMetabibReader() throws Exception {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        BookResourcePort source = mock(BookResourcePort.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn("source-collection");
        when(collections.getCurrentCollection()).thenReturn(collection);

        Book book = mock(Book.class);
        when(book.getId()).thenReturn(BookId.generate());
        when(book.getTitle()).thenReturn("Portable metabib sample");
        when(book.getFileName()).thenReturn("sample.fb2");
        when(book.getAuthors()).thenReturn(List.of());
        when(book.getGenres()).thenReturn(List.of());
        when(book.getLibId()).thenReturn("893718");
        when(book.getFileSize()).thenReturn(400000L); // intentionally stale; real ZIP entry is smaller
        when(book.getIsbn()).thenReturn(com.myhomelibcorp.domain.model.valueobject.Isbn.of("9780306406157"));
        when(book.getPublisher()).thenReturn("Книжкова фабрика");
        when(book.getCity()).thenReturn("Київ");
        when(book.getKeywords()).thenReturn("наука, фантастика");
        when(book.getTranslators()).thenReturn("А. Б. Перекладач");
        when(repository.findPage(any(BookQuery.class))).thenReturn(PageResult.of(List.of(book), 1, 0, 100));
        when(source.readBookData(book)).thenReturn(Optional.of(
                new ByteArrayInputStream("<FictionBook/>".getBytes(StandardCharsets.UTF_8))));
        var exported = new GroupMetabibExportService(repository, source, collections)
                .export(37L, "source-collection", root);
        // Move the complete package: all references must stay relative to the
        // destination directory rather than to the original export location.
        Path relocated = root.resolve("relocated-group");
        Files.move(exported.folder(), relocated);
        Path jsonl = relocated.resolve("catalog.jsonl");
        try (var imported = new MetabibCatalogReader(new ObjectMapper()).open(jsonl)) {
            assertThat(imported.dataset().schema()).isEqualTo("metabib.dataset/1");
            assertThat(imported.hasNext()).isTrue();
            var record = imported.next();
            assertThat(record.title()).isEqualTo("Portable metabib sample");
            assertThat(record.size()).isEqualTo((long) "<FictionBook/>".getBytes(StandardCharsets.UTF_8).length);
            assertThat(record.isbn()).isEqualTo("9780306406157");
            assertThat(record.publisher()).isEqualTo("Книжкова фабрика");
            assertThat(record.publicationCity()).isEqualTo("Київ");
            assertThat(record.keywords()).containsExactly("наука", "фантастика");
            assertThat(record.translators()).hasSize(1);
            assertThat(record.translators().getFirst().displayName()).isEqualTo("А. Б. Перекладач");
            assertThat(record.sourceBookId()).contains("zip-0001");
            assertThat(record.archive()).isEqualTo("books-0001.zip");
            assertThat(record.archiveEntry()).isEqualTo("book-000001.fb2");
            assertThat(relocated.resolve(record.archive())).isRegularFile();
            try (ZipFile zip = new ZipFile(relocated.resolve(record.archive()).toFile())) {
                assertThat(zip.getEntry(record.archiveEntry())).isNotNull();
            }
            assertThat(record.sourceMetadata()).containsEntry("locator.index", "0")
                    .containsEntry("locator.source", "zip-0001");
            assertThat(record.externalIdentities()).anySatisfy(identity -> {
                assertThat(identity.scheme()).isEqualTo("catalog:mhl-source-libid");
                assertThat(identity.value()).isEqualTo("893718");
            });
            assertThat(imported.hasNext()).isFalse();
        }
    }
}
