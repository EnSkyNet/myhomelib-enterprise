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
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void resourceBehindSymlinkedParentIsNotMarkedMissingWhenSupported() throws Exception {
        Fixture f = new Fixture();
        Path root = Files.createDirectories(temp.resolve("sync-root"));
        Path real = Files.createDirectories(root.resolve("real"));
        Files.writeString(real.resolve("book.fb2"), "content");
        Path alias = root.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }
        Book local = Book.builder()
                .id(BookId.generate())
                .title("Book")
                .metadata(BookMetadata.empty())
                .file(new BookFile("book.fb2", "alias", "", 1, root.toString()))
                .local(true)
                .build();
        when(f.scanner.streamSupportedFiles(any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(Stream.empty());
        when(f.books.streamAll()).thenReturn(Stream.of(local));

        f.service.syncFolder(root, SyncOptions.builder().deleteOrphans(true).build());

        verify(f.mutations, never()).updateAvailability(local, false);
    }

    @Test
    void disappearingSyncRootDoesNotMarkEveryBookNonLocal() throws Exception {
        Fixture f = new Fixture();
        Path root = Files.createDirectories(temp.resolve("disappearing-root"));
        Book local = Book.builder().id(BookId.generate()).title("Book")
                .metadata(BookMetadata.empty())
                .file(new BookFile("book.fb2", "", "", 1, root.toString()))
                .local(true).build();
        when(f.scanner.streamSupportedFiles(any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(Stream.empty());
        // Simulate a drive that becomes unavailable between initial enumeration and reconciliation.
        when(f.books.streamAll()).thenAnswer(invocation -> {
            Files.delete(root);
            return Stream.of(local);
        });
        var result = f.service.syncFolder(root, SyncOptions.builder().deleteOrphans(true).build());
        verify(f.mutations, never()).updateAvailability(any(), anyBoolean());
        assertEquals(1, result.getErrors());
    }

    @Test
    void directoryInPlaceOfBookIsNotTreatedAsMissingFile() throws Exception {
        Fixture f = new Fixture();
        Path fake = Files.createDirectories(temp.resolve("actually-a-directory.fb2"));
        Book local = book(true, fake);
        when(f.scanner.streamSupportedFiles(any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(Stream.empty());
        when(f.books.streamAll()).thenReturn(Stream.of(local));
        var result = f.service.syncFolder(temp, SyncOptions.builder().deleteOrphans(true).build());
        verify(f.mutations, never()).updateAvailability(any(), anyBoolean());
        assertEquals(1, result.getErrors());
    }

    @Test
    void restoredFileBetweenChecksNeverBecomesNonLocal() throws Exception {
        Path root = Files.createDirectories(temp.resolve("rechecked-root"));
        Path file = root.resolve("returns.fb2");
        Book local = Book.builder().id(BookId.generate()).title("Returned")
                .metadata(BookMetadata.empty())
                .file(new BookFile("returns.fb2", "", "", 1, root.toString()))
                .local(true).build();
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(queries.streamAll()).thenReturn(Stream.of(local));
        FolderSyncAvailabilitySupport support = spy(new FolderSyncAvailabilitySupport());
        // Deterministically reproduce a restored file at the precise interleaving point.
        doReturn(FolderSyncAvailabilitySupport.Availability.MISSING,
                FolderSyncAvailabilitySupport.Availability.PRESENT)
                .when(support).availabilityOf(file, root);
        var result = support.markMissingPhysicalFiles(root, new java.util.concurrent.atomic.AtomicBoolean(),
                queries, mutations, new FolderSyncBookSupport());
        assertEquals(0, result.updated());
        assertEquals(0, result.errors());
        verify(mutations, never()).updateAvailability(any(), anyBoolean());
    }

    @Test
    void indeterminateSecondCheckCannotClearLocalStatus() throws Exception {
        Path root = Files.createDirectories(temp.resolve("uncertain-root"));
        Path file = root.resolve("uncertain.fb2");
        Book local = Book.builder().id(BookId.generate()).title("Uncertain")
                .metadata(BookMetadata.empty())
                .file(new BookFile("uncertain.fb2", "", "", 1, root.toString()))
                .local(true).build();
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(queries.streamAll()).thenReturn(Stream.of(local));
        FolderSyncAvailabilitySupport support = spy(new FolderSyncAvailabilitySupport());
        doReturn(FolderSyncAvailabilitySupport.Availability.MISSING,
                FolderSyncAvailabilitySupport.Availability.UNKNOWN)
                .when(support).availabilityOf(file, root);
        var result = support.markMissingPhysicalFiles(root, new java.util.concurrent.atomic.AtomicBoolean(),
                queries, mutations, new FolderSyncBookSupport());
        assertEquals(0, result.updated());
        assertEquals(1, result.errors());
        verify(mutations, never()).updateAvailability(any(), anyBoolean());
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
