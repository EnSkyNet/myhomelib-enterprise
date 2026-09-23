package com.myhomelibcorp.application.usecase.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoveLocalBookCopyUseCaseTest {
    @TempDir Path temp;

    private BookResourcePort resources;
    private BookQueryRepository queries;
    private BookCommandRepository commands;
    private CatalogUpdateTrackingPort tracking;
    private StatisticsRepository statistics;
    private CommittedCatalogMutationService committedMutations;
    private CollectionLifecyclePort lifecycle;
    private RemoveLocalBookCopyUseCase useCase;

    @BeforeEach
    void setUp() {
        resources = mock(BookResourcePort.class);
        queries = mock(BookQueryRepository.class);
        commands = mock(BookCommandRepository.class);
        tracking = mock(CatalogUpdateTrackingPort.class);
        statistics = mock(StatisticsRepository.class);
        committedMutations = mock(CommittedCatalogMutationService.class);
        lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(new Collection(
                "collection-test", "Test", temp, null, 0, null, null, null, null));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(committedMutations).executeSynchronized(anyList(), any(Runnable.class));
        useCase = new RemoveLocalBookCopyUseCase(resources, queries, commands, tracking, statistics, committedMutations, lifecycle);
    }

    @Test
    void managedDownloadedFileUsesReversibleStageAndCommitsCatalogOnce() throws Exception {
        BookId id = BookId.generate();
        Path root = temp.resolve("library");
        Path physical = root.resolve("book.fb2");
        BookDto book = dto(id, root, "", "book.fb2", "");
        BookResourcePort.StagedDeletion staged = mock(BookResourcePort.StagedDeletion.class);

        when(queries.findById(id)).thenReturn(java.util.Optional.of(domainBook(id, root, "", "book.fb2", "")));
        when(resources.locateBookFile("book.fb2", "", root.toString(), "")).thenReturn(java.util.Optional.of(physical));
        when(tracking.hasDownloadedBaseline(id)).thenReturn(true);
        when(resources.stagePhysicalFileForDeletion(physical, root, "collection-test", List.of(id))).thenReturn(staged);

        assertThat(useCase.execute(book)).isEqualTo(1);

        verify(resources).stagePhysicalFileForDeletion(physical, root, "collection-test", List.of(id));
        verify(committedMutations).executeSynchronized(eq(List.of(id)), any(Runnable.class));
        verify(commands).updateStorage(id, root.toString(), "", "book.fb2", "", false);
        verify(tracking).clearDownloadedBaseline(id);
        verify(staged).commit();
        verify(staged, never()).rollback();
        verify(statistics).invalidate();
    }

    @Test
    void physicalDeletionWithoutDownloadedProvenanceIsBlockedBeforeFilesystemMutation() throws Exception {
        BookId id = BookId.generate();
        Path root = temp.resolve("library");
        Path physical = root.resolve("imported-original.fb2");
        BookDto book = dto(id, root, "", "imported-original.fb2", "");

        when(queries.findById(id)).thenReturn(java.util.Optional.of(domainBook(id, root, "", "imported-original.fb2", "")));
        when(resources.locateBookFile("imported-original.fb2", "", root.toString(), ""))
                .thenReturn(java.util.Optional.of(physical));
        when(tracking.hasDownloadedBaseline(id)).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(book))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("managed-download provenance");

        verify(resources, never()).stagePhysicalFileForDeletion(any(), any(), any(), anyList());
        verify(committedMutations, never()).executeSynchronized(anyList(), any(Runnable.class));
    }

    @Test
    void sharedArchiveDatabaseFailureRollsBackStagedBytesAndUsesOneCatalogTransaction() throws Exception {
        BookId first = BookId.generate();
        BookId second = BookId.generate();
        Path root = temp.resolve("library");
        Path archive = root.resolve("shared.zip");
        BookDto selected = dto(first, root, "shared.zip", "one.fb2", "one.fb2");
        Book row1 = domainBook(first, root, "shared.zip", "one.fb2", "one.fb2");
        Book row2 = domainBook(second, root, "shared.zip", "two.fb2", "two.fb2");
        BookResourcePort.StagedDeletion staged = mock(BookResourcePort.StagedDeletion.class);

        when(queries.findById(first)).thenReturn(java.util.Optional.of(row1));
        when(resources.locateBookFile("one.fb2", "shared.zip", root.toString(), "one.fb2"))
                .thenReturn(java.util.Optional.of(archive));
        when(queries.findByArchiveContainer(root.toString(), "shared.zip", archive.toString()))
                .thenReturn(List.of(row1, row2));
        when(tracking.hasDownloadedBaseline(first)).thenReturn(true);
        when(resources.stagePhysicalFileForDeletion(archive, root, "collection-test", List.of(first, second))).thenReturn(staged);
        doThrow(new IllegalStateException("fault on second row"))
                .when(commands).updateStorage(second, root.toString(), "shared.zip", "two.fb2", "two.fb2", false);

        assertThatThrownBy(() -> useCase.execute(selected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fault on second row");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookId>> ids = ArgumentCaptor.forClass(List.class);
        verify(committedMutations).executeSynchronized(ids.capture(), any(Runnable.class));
        assertThat(ids.getValue()).containsExactly(first, second);
        verify(staged).rollback();
        verify(staged, never()).commit();
        verify(statistics, never()).invalidate();
    }


    @Test
    void batchStagesSharedStandalonePhysicalFileOnceButMarksEverySelectedRowNonLocal() throws Exception {
        BookId first = BookId.generate();
        BookId second = BookId.generate();
        Path root = temp.resolve("library");
        Path physical = root.resolve("shared.fb2");
        BookDto firstBook = dto(first, root, "", "shared.fb2", "");
        BookDto secondBook = dto(second, root, "", "shared.fb2", "");
        BookResourcePort.StagedDeletion staged = mock(BookResourcePort.StagedDeletion.class);

        when(queries.findById(first)).thenReturn(java.util.Optional.of(domainBook(first, root, "", "shared.fb2", "")));
        when(queries.findById(second)).thenReturn(java.util.Optional.of(domainBook(second, root, "", "shared.fb2", "")));
        when(resources.locateBookFile("shared.fb2", "", root.toString(), ""))
                .thenReturn(java.util.Optional.of(physical));
        when(tracking.hasDownloadedBaseline(first)).thenReturn(true);
        when(tracking.hasDownloadedBaseline(second)).thenReturn(true);
        when(resources.stagePhysicalFileForDeletion(physical, root, "collection-test", List.of(first, second)))
                .thenReturn(staged);

        assertThat(useCase.executeBatch(List.of(firstBook, secondBook))).isEqualTo(2);

        verify(resources).stagePhysicalFileForDeletion(physical, root, "collection-test", List.of(first, second));
        verify(commands).updateStorage(first, root.toString(), "", "shared.fb2", "", false);
        verify(commands).updateStorage(second, root.toString(), "", "shared.fb2", "", false);
        verify(staged).commit();
        verify(staged, never()).rollback();
    }

    @Test
    void batchDatabaseFailureRollsBackEveryStagedPhysicalFile() throws Exception {
        BookId first = BookId.generate();
        BookId second = BookId.generate();
        Path root = temp.resolve("library");
        Path firstPhysical = root.resolve("one.fb2");
        Path secondPhysical = root.resolve("two.fb2");
        BookDto firstBook = dto(first, root, "", "one.fb2", "");
        BookDto secondBook = dto(second, root, "", "two.fb2", "");
        BookResourcePort.StagedDeletion firstStage = mock(BookResourcePort.StagedDeletion.class);
        BookResourcePort.StagedDeletion secondStage = mock(BookResourcePort.StagedDeletion.class);

        when(queries.findById(first)).thenReturn(java.util.Optional.of(domainBook(first, root, "", "one.fb2", "")));
        when(queries.findById(second)).thenReturn(java.util.Optional.of(domainBook(second, root, "", "two.fb2", "")));
        when(resources.locateBookFile("one.fb2", "", root.toString(), ""))
                .thenReturn(java.util.Optional.of(firstPhysical));
        when(resources.locateBookFile("two.fb2", "", root.toString(), ""))
                .thenReturn(java.util.Optional.of(secondPhysical));
        when(tracking.hasDownloadedBaseline(first)).thenReturn(true);
        when(tracking.hasDownloadedBaseline(second)).thenReturn(true);
        when(resources.stagePhysicalFileForDeletion(firstPhysical, root, "collection-test", List.of(first)))
                .thenReturn(firstStage);
        when(resources.stagePhysicalFileForDeletion(secondPhysical, root, "collection-test", List.of(second)))
                .thenReturn(secondStage);
        doThrow(new IllegalStateException("db failure"))
                .when(commands).updateStorage(second, root.toString(), "", "two.fb2", "", false);

        assertThatThrownBy(() -> useCase.executeBatch(List.of(firstBook, secondBook)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db failure");

        verify(firstStage).rollback();
        verify(secondStage).rollback();
        verify(firstStage, never()).commit();
        verify(secondStage, never()).commit();
    }

    private static BookDto dto(BookId id, Path root, String folder, String fileName, String archiveEntry) {
        return BookDto.builder()
                .id(id.asString())
                .title("Test")
                .collectionRoot(root.toString())
                .folder(folder)
                .fileName(fileName)
                .archiveEntry(archiveEntry)
                .local(true)
                .build();
    }

    private static Book domainBook(BookId id, Path root, String folder, String fileName, String archiveEntry) {
        return Book.builder()
                .id(id)
                .title("Book " + UUID.randomUUID())
                .file(new BookFile(fileName, folder, archiveEntry, 1L, root.toString()))
                .local(true)
                .build();
    }
}
