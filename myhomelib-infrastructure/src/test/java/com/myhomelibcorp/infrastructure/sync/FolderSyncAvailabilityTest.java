package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.imports.scanner.LibraryScanner;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.sync.SyncOptions;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.infrastructure.importengine.InpxImportPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolderSyncAvailabilityTest {

    @TempDir
    Path temp;

    @Test
    void missingFileDoesNotDeleteBookOrUserState() throws Exception {
        Fixture f = new Fixture();
        Book local = book(true, temp.resolve("missing.fb2"));
        when(f.scanner.streamSupportedFiles(any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(Stream.empty());
        when(f.books.streamAll()).thenReturn(Stream.of(local));

        f.service.syncFolder(temp, SyncOptions.builder().deleteOrphans(true).build());

        verify(f.mutations).updateAvailability(local, false);
    }

    @Test
    void returningFileRestoresLocalFlagEvenWhenMetadataRefreshIsDisabled() throws Exception {
        Fixture f = new Fixture();
        Path file = Files.writeString(temp.resolve("back.fb2"), "content");
        Book remoteMarked = book(false, file);
        when(f.scanner.streamSupportedFiles(any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(Stream.of(file));
        when(f.books.findByStorage(temp.toString(), "", "back.fb2", ""))
                .thenReturn(Optional.of(remoteMarked));

        f.service.syncFolder(temp, SyncOptions.builder().updateChanged(false).build());

        verify(f.mutations).updateAvailability(remoteMarked, true);
        verifyNoInteractions(f.importers);
    }

    private Book book(boolean local, Path file) {
        return Book.builder()
                .id(BookId.generate())
                .title("Book")
                .metadata(BookMetadata.empty())
                .file(new BookFile(file.getFileName().toString(), "", "", 1, temp.toString()))
                .local(local)
                .build();
    }

    private static final class Fixture {
        final BookQueryRepository books = mock(BookQueryRepository.class);
        final CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        final SearchIndexSynchronizer searchSync = mock(SearchIndexSynchronizer.class);
        final LibraryScanner scanner = mock(LibraryScanner.class);
        final ImporterRegistry importers = mock(ImporterRegistry.class);
        final InpxImportPipeline inpx = mock(InpxImportPipeline.class);
        final FolderSyncService service = new FolderSyncService(
                books, mutations, searchSync, scanner, importers, inpx);
    }
}
