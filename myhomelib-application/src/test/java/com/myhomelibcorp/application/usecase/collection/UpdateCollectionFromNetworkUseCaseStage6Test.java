package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.catalog.CatalogSourceState;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.catalog.CatalogSourceStatePort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogPackage;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdateCollectionFromNetworkUseCaseStage6Test {

    @Test
    void passesStableIdentityUsesDeltaIndexAndAdvancesVersionOnlyAtEnd(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture();
        Collection collection = collection("collection-42", tempDir);
        Path downloaded = tempDir.resolve("catalog.inpx");
        Files.write(downloaded, new byte[]{1, 2, 3});
        String server = "https://alex80.github.io/mhl/download/inpx/";
        String effectiveUrl = "https://alex80.github.io/mhl/update/extra_flibusta_online_fb2.zip";
        ImportResult imported = new ImportResult(17, 0, 0, 0, 25,
                ImportStatus.SUCCESS, new ImportChangeSet(Set.of(), Set.of(), Set.of(), true), List.of());

        when(f.lifecycle.getCurrentCollection()).thenReturn(collection);
        when(f.state.get("remote-collection:collection-42"))
                .thenReturn(new CatalogSourceState("remote-collection:collection-42", "", "", "20260126", "", "", "", "", "", ""));
        when(f.downloader.downloadUpdates(eq(collection), eq(server), eq("20260126"), any(AtomicBoolean.class), any(DoubleConsumer.class), any(Consumer.class)))
                .thenReturn(RemoteCatalogUpdatePlan.of(
                        List.of(RemoteCatalogPackage.of(downloaded, effectiveUrl, "20260825", false)), "20260825"));
        when(f.importer.execute(any(ImportContext.class))).thenReturn(imported);

        // Використовуємо f.useCase замість змінної useCase
        ImportResult result = f.useCase.execute(collection, server, new AtomicBoolean(false), p -> {});
        assertThat(result.imported()).isEqualTo(17);

        ArgumentCaptor<ImportContext> context = ArgumentCaptor.forClass(ImportContext.class);
        verify(f.importer).execute(context.capture());
        assertThat(context.getValue().getCatalogSourceKey()).isEqualTo("remote-collection:collection-42");
        assertThat(context.getValue().getCatalogSourceLocation()).isEqualTo(effectiveUrl);
        assertThat(context.getValue().isCatalogFullSnapshot()).isFalse();
        verify(f.search).beginAtomicUpdate();
        verify(f.search).commit();
        verify(f.search, never()).rebuildIndex();
        verify(f.state).recordApplied("remote-collection:collection-42", "20260825");
        InOrder lifecycleOrder = inOrder(f.search, f.statistics, f.state);
        lifecycleOrder.verify(f.search).commit();
        lifecycleOrder.verify(f.statistics).invalidate();
        lifecycleOrder.verify(f.statistics).refreshStatistics();
        lifecycleOrder.verify(f.state).recordApplied("remote-collection:collection-42", "20260825");
    }

    @Test
    void remoteCatalogWithoutExplicitRootUsesPermanentDownloadsDirectory(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture();
        Collection collection = new Collection("c-download-root", "Online", null, null, 2, null, null,
                "https://example.test/books", null);
        Path downloaded = tempDir.resolve("catalog.inpx");
        Files.write(downloaded, new byte[]{1});
        when(f.lifecycle.getCurrentCollection()).thenReturn(collection);
        when(f.state.get("remote-collection:c-download-root"))
                .thenReturn(CatalogSourceState.empty("remote-collection:c-download-root"));
        when(f.downloader.downloadUpdates(eq(collection), anyString(), anyString(), any(AtomicBoolean.class), any(DoubleConsumer.class), any(Consumer.class)))
                .thenReturn(RemoteCatalogUpdatePlan.of(
                        List.of(RemoteCatalogPackage.of(downloaded, "https://example.test/full.zip", "20260830", true)), "20260830"));
        when(f.importer.execute(any())).thenReturn(new ImportResult(1, 0, 0, 0, 1,
                ImportStatus.SUCCESS, ImportChangeSet.empty(true), List.of()));

        f.useCase.execute(collection, "https://example.test/catalog", null, null);

        ArgumentCaptor<ImportContext> context = ArgumentCaptor.forClass(ImportContext.class);
        verify(f.importer).execute(context.capture());
        assertThat(context.getValue().getRootDirectory())
                .isEqualTo(com.myhomelibcorp.shared.util.AppPaths.downloadsDir().resolve("c-download-root").toAbsolutePath().normalize());
        assertThat(context.getValue().getRootDirectory()).isNotEqualTo(downloaded.getParent());
    }

    @Test
    void doesNotImportOrRebuildWhenServerIsCurrent(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture();
        Collection collection = collection("c1", tempDir);
        when(f.lifecycle.getCurrentCollection()).thenReturn(collection);
        when(f.state.get("remote-collection:c1"))
                .thenReturn(new CatalogSourceState("remote-collection:c1", "", "", "20260825", "", "", "", "", "", ""));
        when(f.downloader.downloadUpdates(eq(collection), anyString(), eq("20260825"), any(AtomicBoolean.class), any(DoubleConsumer.class), any(Consumer.class)))
                .thenReturn(RemoteCatalogUpdatePlan.of(List.of(), "20260825"));

        ImportResult result = f.useCase.execute(collection, "https://alex80.github.io/mhl/download/inpx/", null, null);
        assertThat(result.imported()).isZero();
        verifyNoInteractions(f.importer);
        verify(f.search, never()).rebuildIndex();
        verify(f.state, never()).recordApplied(anyString(), anyString());
    }

    @Test
    void failedIndexDoesNotAdvanceAppliedVersion(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture();
        Collection collection = collection("c1", tempDir);
        Path downloaded = tempDir.resolve("delta.inpx");
        Files.write(downloaded, new byte[]{1});
        when(f.lifecycle.getCurrentCollection()).thenReturn(collection);
        when(f.state.get("remote-collection:c1")).thenReturn(CatalogSourceState.empty("remote-collection:c1"));
        when(f.downloader.downloadUpdates(eq(collection), anyString(), anyString(), any(AtomicBoolean.class), any(DoubleConsumer.class), any(Consumer.class)))
                .thenReturn(RemoteCatalogUpdatePlan.of(
                        List.of(RemoteCatalogPackage.of(downloaded, "https://example.test/delta.zip", "20260825", false)), "20260825"));
        when(f.importer.execute(any())).thenReturn(new ImportResult(1, 0, 0, 0, 1,
                ImportStatus.SUCCESS, new ImportChangeSet(Set.of(), Set.of("00000000-0000-0000-0000-000000000001"), Set.of(), true), List.of()));
        when(f.books.findByIds(anyList())).thenReturn(List.of());
        doThrow(new IllegalStateException("commit failed")).when(f.search).commit();

        assertThatThrownBy(() ->
                f.useCase.execute(collection, "https://example.test/catalog.inpx", null, null))
                .hasMessageContaining("commit failed");
        verify(f.search).rollbackAtomicUpdate();
        verify(f.state, never()).recordApplied(anyString(), anyString());
        verify(f.state).recordFailure(eq("remote-collection:c1"), anyString());
    }

    @Test
    void failedStatisticsRefreshDoesNotAdvanceAppliedVersion(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture();
        Collection collection = collection("c1", tempDir);
        Path downloaded = tempDir.resolve("delta.inpx");
        Files.write(downloaded, new byte[]{1});
        when(f.lifecycle.getCurrentCollection()).thenReturn(collection);
        when(f.state.get("remote-collection:c1")).thenReturn(CatalogSourceState.empty("remote-collection:c1"));
        when(f.downloader.downloadUpdates(eq(collection), anyString(), anyString(), any(AtomicBoolean.class), any(DoubleConsumer.class), any(Consumer.class)))
                .thenReturn(RemoteCatalogUpdatePlan.of(
                        List.of(RemoteCatalogPackage.of(downloaded, "https://example.test/delta.zip", "20260825", false)), "20260825"));
        when(f.importer.execute(any())).thenReturn(new ImportResult(1, 0, 0, 0, 1,
                ImportStatus.SUCCESS, ImportChangeSet.empty(true), List.of()));
        doThrow(new IllegalStateException("statistics failed")).when(f.statistics).refreshStatistics();

        assertThatThrownBy(() ->
                f.useCase.execute(collection, "https://example.test/catalog.inpx", null, null))
                .hasMessageContaining("statistics failed");

        verify(f.statistics).invalidate();
        verify(f.statistics).refreshStatistics();
        verify(f.state, never()).recordApplied(anyString(), anyString());
        verify(f.state).recordFailure(eq("remote-collection:c1"), anyString());
    }

    @Test
    void refusesNonActiveCollection(@TempDir Path tempDir) {
        Fixture f = new Fixture();
        Collection requested = collection("c1", tempDir);
        Collection active = collection("c2", tempDir);
        when(f.lifecycle.getCurrentCollection()).thenReturn(active);

        assertThatThrownBy(() ->
                f.useCase.execute(requested, "https://example.test/catalog.inpx", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("активну колекцію");
        verifyNoInteractions(f.downloader, f.importer, f.state, f.search, f.books);
    }

    private static Collection collection(String id, Path root) {
        return new Collection(id, "Online", root, null, 2, null, null, "https://example.test/books", null);
    }

    private static final class Fixture {
        final RemoteCatalogDownloadPort downloader = mock(RemoteCatalogDownloadPort.class);
        final ImportFileUseCase importer = mock(ImportFileUseCase.class);
        final CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        final CatalogSourceStatePort state = mock(CatalogSourceStatePort.class);
        final SearchIndexer search = mock(SearchIndexer.class);
        final BookQueryRepository books = mock(BookQueryRepository.class);
        final StatisticsRepository statistics = mock(StatisticsRepository.class);
        final UpdateCollectionFromNetworkUseCase useCase;

        Fixture() {
            this.useCase = new UpdateCollectionFromNetworkUseCase(
                    downloader, importer, lifecycle, state, search, books, statistics,
                    50_000, new com.myhomelibcorp.application.operation.LibraryOperationCoordinator()
            );
        }
    }
}