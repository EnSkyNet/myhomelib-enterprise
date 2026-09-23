package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.content.indexing.ContentIndexAutoSchedulingService;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.port.out.collection.BookUserStateTransferPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CopyBooksBetweenCollectionsUseCaseTest {

    @TempDir
    Path tempDir;

    @Test
    void rebuildsNonReusableTargetOnceThenUsesPostCommitSelectiveSync() throws Exception {
        Fixture f = new Fixture(tempDir);
        when(f.lifecycle.initializeCollection(f.target, false)).thenReturn(false);
        f.savedBatchSucceeds();
        when(f.searchSync.rebuildSafelyNow()).thenReturn(true);
        when(f.searchSync.synchronizeSafelyNow(anyList())).thenReturn(true);

        CopyBooksBetweenCollectionsUseCase.Result result = f.useCase.execute(List.of(f.bookId), "target");

        org.assertj.core.api.Assertions.assertThat(result.copied()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.failed()).isZero();
        verify(f.searchSync).rebuildSafelyNow();
        verify(f.bookSaver).saveBatchReturningSaved(anyList(), eq(false), eq(DuplicatePolicy.SKIP), any());
        verify(f.contentIndexScheduling).markStale("target");
        verify(f.searchSync).synchronizeSafelyNow(List.of(f.bookId));
        verify(f.userState).transferCopiedBookState(f.source, f.target, List.of(f.bookId));
        org.assertj.core.api.Assertions.assertThat(f.managedFiles()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(Files.readString(f.managedFiles().get(0))).isEqualTo("payload");
    }

    @Test
    void postCommitLuceneFailureRetainsPhysicalFileInsteadOfCreatingBrokenCatalogRow() throws Exception {
        Fixture f = new Fixture(tempDir);
        when(f.lifecycle.initializeCollection(f.target, false)).thenReturn(true);
        f.savedBatchSucceeds();
        when(f.searchSync.synchronizeSafelyNow(anyList())).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> f.useCase.execute(List.of(f.bookId), "target"));

        verify(f.userState).transferCopiedBookState(f.source, f.target, List.of(f.bookId));
        verify(f.contentIndexScheduling).markStale("target");
        org.assertj.core.api.Assertions.assertThat(f.managedFiles()).hasSize(1);
        verify(f.lifecycle).initializeCollection(f.source, true);
    }

    @Test
    void stateTransferFailureCleansPhysicalFileBecauseTargetTransactionDidNotCommit() throws Exception {
        Fixture f = new Fixture(tempDir);
        when(f.lifecycle.initializeCollection(f.target, false)).thenReturn(true);
        when(f.bookSaver.saveBatchReturningSaved(anyList(), eq(false), eq(DuplicatePolicy.SKIP), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<List<Book>> hook = invocation.getArgument(3);
                    List<Book> batch = List.copyOf(invocation.getArgument(0));
                    hook.accept(batch);
                    return batch;
                });
        doThrow(new IllegalStateException("state transfer failed"))
                .when(f.userState).transferCopiedBookState(eq(f.source), eq(f.target), anyList());

        CopyBooksBetweenCollectionsUseCase.Result result = f.useCase.execute(List.of(f.bookId), "target");

        org.assertj.core.api.Assertions.assertThat(result.copied()).isZero();
        org.assertj.core.api.Assertions.assertThat(result.failed()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(f.managedFiles()).isEmpty();
        verifyNoInteractions(f.searchSync);
    }

    @Test
    void rollbackPreservesForeignReplacementInsteadOfDeletingIt() throws Exception {
        Fixture f = new Fixture(tempDir);
        when(f.lifecycle.initializeCollection(f.target, false)).thenReturn(true);
        when(f.bookSaver.saveBatchReturningSaved(anyList(), eq(false), eq(DuplicatePolicy.SKIP), any()))
                .thenAnswer(invocation -> {
                    List<Book> batch = List.copyOf(invocation.getArgument(0));
                    Book staged = batch.getFirst();
                    Path published = f.targetRoot.resolve(staged.getFolder()).resolve(staged.getFileName());
                    Files.writeString(published, "foreign replacement");
                    throw new IllegalStateException("forced pre-commit failure");
                });

        CopyBooksBetweenCollectionsUseCase.Result result = f.useCase.execute(List.of(f.bookId), "target");

        org.assertj.core.api.Assertions.assertThat(result.copied()).isZero();
        org.assertj.core.api.Assertions.assertThat(result.failed()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(f.managedFiles()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(Files.readString(f.managedFiles().getFirst()))
                .isEqualTo("foreign replacement");
    }

    @Test
    void rejectsSymlinkedManagedFolderWithoutWritingOutsideWhenSupported() throws Exception {
        Fixture f = new Fixture(tempDir);
        Path managed = f.targetRoot.resolve(".myhomelib-copied");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(managed, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable: " + unsupported);
        }

        assertThrows(IllegalStateException.class,
                () -> f.useCase.execute(List.of(f.bookId), "target"));
        try (var stream = Files.list(outside)) {
            org.assertj.core.api.Assertions.assertThat(stream.toList()).isEmpty();
        }
    }


    @Test
    void sourceReactivationFailureIsNotReportedAsSuccessfulCopy() throws Exception {
        Fixture f = new Fixture(tempDir);
        when(f.lifecycle.initializeCollection(f.target, false)).thenReturn(true);
        f.savedBatchSucceeds();
        when(f.searchSync.synchronizeSafelyNow(anyList())).thenReturn(true);
        doThrow(new IllegalStateException("source reopen failed"))
                .when(f.lifecycle).initializeCollection(f.source, true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> f.useCase.execute(List.of(f.bookId), "target"));

        org.assertj.core.api.Assertions.assertThat(failure.getMessage())
                .contains("не вдалося повернутися до вихідної колекції");
        verify(f.bookSaver).saveBatchReturningSaved(anyList(), eq(false), eq(DuplicatePolicy.SKIP), any());
        org.assertj.core.api.Assertions.assertThat(f.managedFiles()).hasSize(1);
    }

    @Test
    void sourceReactivationFailureIsSuppressedWhenCopyAlreadyFailed() throws Exception {
        Fixture f = new Fixture(tempDir);
        when(f.lifecycle.initializeCollection(f.target, false)).thenThrow(new IllegalStateException("target open failed"));
        doThrow(new IllegalStateException("source reopen failed"))
                .when(f.lifecycle).initializeCollection(f.source, true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> f.useCase.execute(List.of(f.bookId), "target"));

        org.assertj.core.api.Assertions.assertThat(failure.getMessage()).contains("target open failed");
        org.assertj.core.api.Assertions.assertThat(failure.getSuppressed())
                .extracting(Throwable::getMessage)
                .contains("source reopen failed");
    }

    @Test
    void duplicateSkipRemovesUnusedCopiedFileAndDoesNotTouchLucene() throws Exception {
        Fixture f = new Fixture(tempDir);
        when(f.lifecycle.initializeCollection(f.target, false)).thenReturn(true);
        when(f.bookSaver.saveBatchReturningSaved(anyList(), eq(false), eq(DuplicatePolicy.SKIP), any()))
                .thenReturn(List.of());

        CopyBooksBetweenCollectionsUseCase.Result result = f.useCase.execute(List.of(f.bookId), "target");

        org.assertj.core.api.Assertions.assertThat(result.copied()).isZero();
        org.assertj.core.api.Assertions.assertThat(result.failed()).isZero();
        org.assertj.core.api.Assertions.assertThat(f.managedFiles()).isEmpty();
        verifyNoInteractions(f.searchSync, f.userState);
    }

    private static final class Fixture {
        final BookQueryRepository books = mock(BookQueryRepository.class);
        final CollectionRepository collections = mock(CollectionRepository.class);
        final BookResourcePort resources = mock(BookResourcePort.class);
        final CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        final BookSaver bookSaver = mock(BookSaver.class);
        final BookUserStateTransferPort userState = mock(BookUserStateTransferPort.class);
        final SearchIndexSynchronizer searchSync = mock(SearchIndexSynchronizer.class);
        final ContentIndexAutoSchedulingService contentIndexScheduling = mock(ContentIndexAutoSchedulingService.class);
        final Collection source = mock(Collection.class);
        final Collection target = mock(Collection.class);
        final Book sourceBook = mock(Book.class);
        final BookId bookId = BookId.generate();
        final Path targetRoot;
        final CopyBooksBetweenCollectionsUseCase useCase;

        Fixture(Path tempDir) throws Exception {
            targetRoot = tempDir.resolve("target-root");
            Files.createDirectories(targetRoot);
            when(source.getId()).thenReturn("source");
            when(source.getName()).thenReturn("Source");
            when(target.getId()).thenReturn("target");
            when(target.getName()).thenReturn("Target");
            when(target.getRootFolder()).thenReturn(targetRoot);
            when(lifecycle.getCurrentCollection()).thenReturn(source);
            when(collections.findById("target")).thenReturn(Optional.of(target));
            when(lifecycle.initializeCollection(source, true)).thenReturn(true);

            when(sourceBook.getId()).thenReturn(bookId);
            when(sourceBook.getTitle()).thenReturn("Copied Book");
            when(sourceBook.getFileName()).thenReturn("source.fb2");
            when(sourceBook.getFolder()).thenReturn("");
            when(sourceBook.getCollectionRoot()).thenReturn(tempDir.toString());
            when(sourceBook.getArchiveEntry()).thenReturn("");
            when(sourceBook.getAuthors()).thenReturn(List.of());
            when(sourceBook.getGenres()).thenReturn(List.of());
            when(books.findByIds(anyList())).thenReturn(List.of(sourceBook), List.of());
            when(resources.readBookData(sourceBook)).thenReturn(Optional.of(
                    new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))));

            useCase = new CopyBooksBetweenCollectionsUseCase(
                    books, collections, resources, lifecycle, bookSaver, userState, searchSync, contentIndexScheduling);
        }

        void savedBatchSucceeds() {
            when(bookSaver.saveBatchReturningSaved(anyList(), eq(false), eq(DuplicatePolicy.SKIP), any()))
                    .thenAnswer(invocation -> {
                        List<Book> batch = List.copyOf(invocation.getArgument(0));
                        @SuppressWarnings("unchecked")
                        Consumer<List<Book>> hook = invocation.getArgument(3);
                        hook.accept(batch);
                        return batch;
                    });
        }

        List<Path> managedFiles() throws Exception {
            Path managed = targetRoot.resolve(".myhomelib-copied");
            if (!Files.isDirectory(managed)) return List.of();
            try (var stream = Files.list(managed)) {
                return stream.filter(Files::isRegularFile).toList();
            }
        }
    }
}
