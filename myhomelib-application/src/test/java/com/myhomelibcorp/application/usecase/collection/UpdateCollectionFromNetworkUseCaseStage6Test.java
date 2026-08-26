package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogPackage;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UpdateCollectionFromNetworkUseCaseStage6Test {
    @Test
    void passesStableCollectionIdentityAndDeltaMode(@TempDir Path tempDir) throws Exception {
        RemoteCatalogDownloadPort downloader = mock(RemoteCatalogDownloadPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        UpdateCollectionFromNetworkUseCase useCase =
                new UpdateCollectionFromNetworkUseCase(downloader, importer, lifecycle, settings);

        Collection collection = new Collection("collection-42", "Online", tempDir, null, 2,
                null, null, "https://example.test/books", null);
        Path downloaded = tempDir.resolve("catalog-random-9371.inpx");
        Files.write(downloaded, new byte[]{1, 2, 3});
        String server = "https://alex80.github.io/mhl/download/inpx/";
        String effectiveUrl = "https://alex80.github.io/mhl/update/extra_flibusta_online_fb2.zip";
        ImportResult result = new ImportResult(17, 0, 0, 0, 25);

        when(lifecycle.getCurrentCollection()).thenReturn(collection);
        when(settings.get(UpdateCollectionFromNetworkUseCase.versionKey("collection-42"), ""))
                .thenReturn("20260126");
        when(downloader.downloadUpdates(eq(collection), eq(server), eq("20260126"), any(), any()))
                .thenReturn(new RemoteCatalogUpdatePlan(
                        List.of(new RemoteCatalogPackage(downloaded, effectiveUrl, "20260825", false)),
                        "20260825"));
        when(importer.execute(any(ImportContext.class))).thenReturn(result);

        assertThat(useCase.execute(collection, server, new AtomicBoolean(false), p -> {})).isEqualTo(result);

        ArgumentCaptor<ImportContext> context = ArgumentCaptor.forClass(ImportContext.class);
        verify(importer).execute(context.capture());
        assertThat(context.getValue().getCatalogSourceKey()).isEqualTo("remote-collection:collection-42");
        assertThat(context.getValue().getCatalogSourceLocation()).isEqualTo(effectiveUrl);
        assertThat(context.getValue().isCatalogFullSnapshot()).isFalse();
        assertThat(context.getValue().getCatalogSourceKey()).doesNotContain(downloaded.getFileName().toString());
        verify(settings).put(UpdateCollectionFromNetworkUseCase.versionKey("collection-42"), "20260825");
        verify(lifecycle).rebuildSearchIndex();
    }

    @Test
    void doesNotRebuildIndexWhenServerIsAlreadyCurrent(@TempDir Path tempDir) throws Exception {
        RemoteCatalogDownloadPort downloader = mock(RemoteCatalogDownloadPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        UpdateCollectionFromNetworkUseCase useCase =
                new UpdateCollectionFromNetworkUseCase(downloader, importer, lifecycle, settings);
        Collection collection = new Collection("c1", "Online", tempDir, null, 2,
                null, null, null, null);
        when(lifecycle.getCurrentCollection()).thenReturn(collection);
        when(settings.get(UpdateCollectionFromNetworkUseCase.versionKey("c1"), "")).thenReturn("20260825");
        when(downloader.downloadUpdates(eq(collection), anyString(), eq("20260825"), any(), any()))
                .thenReturn(new RemoteCatalogUpdatePlan(List.of(), "20260825"));

        ImportResult result = useCase.execute(collection, "https://alex80.github.io/mhl/download/inpx/", null, null);

        assertThat(result.imported()).isZero();
        verifyNoInteractions(importer);
        verify(lifecycle, never()).rebuildSearchIndex();
    }

    @Test
    void refusesToImportIntoACollectionThatIsNotActive(@TempDir Path tempDir) {
        RemoteCatalogDownloadPort downloader = mock(RemoteCatalogDownloadPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        UpdateCollectionFromNetworkUseCase useCase =
                new UpdateCollectionFromNetworkUseCase(downloader, importer, lifecycle, settings);

        Collection requested = new Collection("c1", "Requested", tempDir, null, 2,
                null, null, null, null);
        Collection active = new Collection("c2", "Active", tempDir, null, 0,
                null, null, null, null);
        when(lifecycle.getCurrentCollection()).thenReturn(active);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        useCase.execute(requested, "https://example.test/catalog.inpx", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("активну колекцію");

        verifyNoInteractions(downloader, importer, settings);
    }
}
