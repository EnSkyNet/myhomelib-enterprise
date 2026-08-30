package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.imports.scanner.LibraryScanner;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.sync.SyncOptions;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.importengine.InpxImportPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolderSyncServiceTest {
    @TempDir Path temp;

    @Test
    void changedLooseFileReReadsMetadataAndPreservesUserStateAndIdentity() throws Exception {
        Path file = temp.resolve("book.fb2");
        Files.writeString(file, "changed-content");

        BookQueryRepository queries = mock(BookQueryRepository.class);
        BookCommandRepository commands = mock(BookCommandRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);
        BookImporterPort importer = mock(BookImporterPort.class);

        BookId id = BookId.generate();
        Book existing = book(id, "Old title", new BookMetadataBuilder()
                .annotation("old annotation").review("my review").libId("LIB-42").rate(5).progress(63).build(),
                new BookFile("book.fb2", "", "", 1, temp.toAbsolutePath().normalize().toString()), LocalDateTime.now().minusDays(1));
        Book parsed = book(BookId.generate(), "New title", new BookMetadataBuilder()
                .annotation("new annotation").language("en").build(),
                new BookFile("book.fb2", temp.toString(), "", Files.size(file), null), LocalDateTime.now());

        Path root = temp.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        when(scanner.streamSupportedFiles(eq(root), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(Stream.of(absolute));
        when(queries.findByStorage(eq(root.toString()), eq(""), eq("book.fb2"), eq("")))
                .thenReturn(Optional.of(existing));
        when(registry.findImporter(absolute)).thenReturn(importer);
        when(importer.importBooks(absolute)).thenReturn(Stream.of(parsed));

        FolderSyncService service = new FolderSyncService(queries, commands, indexer, synchronizer, scanner, registry, inpx);
        var result = service.syncFolder(temp, SyncOptions.builder().updateChanged(true).build());

        assertThat(result.getUpdated()).isEqualTo(1);
        ArgumentCaptor<Book> saved = ArgumentCaptor.forClass(Book.class);
        verify(commands).save(saved.capture());
        Book value = saved.getValue();
        assertThat(value.getId()).isEqualTo(id);
        assertThat(value.getTitle()).isEqualTo("New title");
        assertThat(value.getAnnotation()).isEqualTo("new annotation");
        assertThat(value.getRate()).isEqualTo(5);
        assertThat(value.getProgress()).isEqualTo(63);
        assertThat(value.getReview()).isEqualTo("my review");
        assertThat(value.getLibId()).isEqualTo("LIB-42");
        verify(indexer).indexBook(value);
        verify(indexer).commit();
        verifyNoInteractions(inpx);
    }

    @Test
    void newLooseFileUsesFormatImporterInsteadOfInpxPipeline() throws Exception {
        Path file = temp.resolve("book.txt");
        Files.writeString(file, "A title\nBody");

        BookQueryRepository queries = mock(BookQueryRepository.class);
        BookCommandRepository commands = mock(BookCommandRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);
        BookImporterPort importer = mock(BookImporterPort.class);
        Book parsed = book(BookId.generate(), "A title", new BookMetadataBuilder().build(),
                new BookFile("book.txt", temp.toString(), "", Files.size(file), null), LocalDateTime.now());

        Path root = temp.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        when(scanner.streamSupportedFiles(eq(root), anyBoolean(), anyInt(), anyLong())).thenReturn(Stream.of(absolute));
        when(queries.findByStorage(anyString(), anyString(), eq("book.txt"), eq(""))).thenReturn(Optional.empty());
        when(registry.findImporter(absolute)).thenReturn(importer);
        when(importer.importBooks(absolute)).thenReturn(Stream.of(parsed));

        FolderSyncService service = new FolderSyncService(queries, commands, indexer, synchronizer, scanner, registry, inpx);
        var result = service.syncFolder(temp, SyncOptions.builder().build());

        assertThat(result.getAdded()).isEqualTo(1);
        verify(commands).save(any(Book.class));
        verifyNoInteractions(inpx);
    }


    @Test
    void scannerFailureIsCountedOnce() throws Exception {
        BookQueryRepository queries = mock(BookQueryRepository.class);
        BookCommandRepository commands = mock(BookCommandRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);

        Path root = temp.toAbsolutePath().normalize();
        when(scanner.streamSupportedFiles(eq(root), anyBoolean(), anyInt(), anyLong()))
                .thenThrow(new java.io.IOException("scan failed"));

        FolderSyncService service = new FolderSyncService(queries, commands, indexer, synchronizer, scanner, registry, inpx);
        var result = service.syncFolder(temp, SyncOptions.builder().build());

        assertThat(result.getErrors()).isEqualTo(1);
        assertThat(result.getErrorMessages()).hasSize(1);
        verifyNoInteractions(commands, indexer, registry, inpx);
    }


    @Test
    void multipleInpxFilesUseOneBoundedSelectiveLuceneFinalization() throws Exception {
        Path first = temp.resolve("first.inpx").toAbsolutePath().normalize();
        Path second = temp.resolve("second.inpx").toAbsolutePath().normalize();
        Files.writeString(first, "fixture");
        Files.writeString(second, "fixture");

        BookQueryRepository queries = mock(BookQueryRepository.class);
        BookCommandRepository commands = mock(BookCommandRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);

        BookId inserted = BookId.generate();
        BookId updated = BookId.generate();
        ImportResult firstResult = new ImportResult(1, 0, 0, 0, 10, ImportStatus.SUCCESS,
                new ImportChangeSet(Set.of(inserted.asString()), Set.of(), Set.of(), true), List.of());
        ImportResult secondResult = new ImportResult(1, 0, 0, 0, 10, ImportStatus.SUCCESS,
                new ImportChangeSet(Set.of(), Set.of(updated.asString()), Set.of(), true), List.of());

        Path root = temp.toAbsolutePath().normalize();
        when(scanner.streamSupportedFiles(eq(root), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(Stream.of(first, second));
        when(inpx.importFileWithResult(eq(first), eq(1000), eq(root), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(firstResult);
        when(inpx.importFileWithResult(eq(second), eq(1000), eq(root), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(secondResult);
        when(synchronizer.synchronizeSafelyNow(anyList())).thenReturn(true);

        FolderSyncService service = new FolderSyncService(queries, commands, indexer, synchronizer, scanner, registry, inpx);
        var result = service.syncFolder(temp, SyncOptions.builder().build());

        assertThat(result.getAdded()).isEqualTo(1);
        assertThat(result.getUpdated()).isEqualTo(1);
        verify(synchronizer, times(1)).synchronizeSafelyNow(argThat(ids ->
                ids.size() == 2 && ids.contains(inserted) && ids.contains(updated)));
        verify(indexer, never()).rebuildIndex();
        verify(indexer, never()).commit();
        verifyNoInteractions(commands, registry);
    }

    @Test
    void incompleteInpxTrackingTriggersOnlyOneFullRebuildAfterAllFiles() throws Exception {
        Path first = temp.resolve("large-a.inpx").toAbsolutePath().normalize();
        Path second = temp.resolve("large-b.inpx").toAbsolutePath().normalize();
        Files.writeString(first, "fixture");
        Files.writeString(second, "fixture");

        BookQueryRepository queries = mock(BookQueryRepository.class);
        BookCommandRepository commands = mock(BookCommandRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexSynchronizer synchronizer = mock(SearchIndexSynchronizer.class);
        LibraryScanner scanner = mock(LibraryScanner.class);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        InpxImportPipeline inpx = mock(InpxImportPipeline.class);

        ImportChangeSet overflow = new ImportChangeSet(Set.of(), Set.of(), Set.of(), false, 60_000, 0, 0);
        ImportResult large = new ImportResult(60_000, 0, 0, 0, 10, ImportStatus.SUCCESS, overflow, List.of());
        Path root = temp.toAbsolutePath().normalize();
        when(scanner.streamSupportedFiles(eq(root), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(Stream.of(first, second));
        when(inpx.importFileWithResult(any(Path.class), eq(1000), eq(root), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(large);

        FolderSyncService service = new FolderSyncService(queries, commands, indexer, synchronizer, scanner, registry, inpx);
        var result = service.syncFolder(temp, SyncOptions.builder().build());

        assertThat(result.getAdded()).isEqualTo(120_000);
        verify(indexer, times(1)).rebuildIndex();
        verifyNoInteractions(synchronizer);
        verify(indexer, never()).commit();
    }

    private Book book(BookId id, String title, BookMetadata metadata, BookFile file, LocalDateTime update) {
        return Book.builder()
                .id(id).title(title)
                .authors(List.of(new Author("", "", "Author")))
                .genres(List.of())
                .metadata(metadata).file(file)
                .updateDate(update).createdAt(LocalDateTime.now().minusYears(1))
                .local(true).build();
    }

    private static final class BookMetadataBuilder {
        private String annotation = "";
        private String review = "";
        private String libId = "";
        private int rate;
        private int progress;
        private String language = "uk";

        BookMetadataBuilder annotation(String value) { annotation = value; return this; }
        BookMetadataBuilder review(String value) { review = value; return this; }
        BookMetadataBuilder libId(String value) { libId = value; return this; }
        BookMetadataBuilder rate(int value) { rate = value; return this; }
        BookMetadataBuilder progress(int value) { progress = value; return this; }
        BookMetadataBuilder language(String value) { language = value; return this; }
        BookMetadata build() {
            return BookMetadata.builder().annotation(annotation).keywords("")
                    .language(LanguageCode.of(language)).review(review).libId(libId)
                    .rate(rate).progress(progress).build();
        }
    }
}
