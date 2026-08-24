package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UpdateCollectionFromNetworkUseCaseStage6Test {
    @Test
    void passesStableCollectionIdentityInsteadOfDownloadedTempPath(@TempDir Path tempDir) throws Exception {
        RemoteCatalogDownloadPort downloader = mock(RemoteCatalogDownloadPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        UpdateCollectionFromNetworkUseCase useCase = new UpdateCollectionFromNetworkUseCase(downloader, importer, lifecycle);

        Collection collection = new Collection("collection-42", "Online", tempDir, null, 1,
                null, null, "https://example.test/books", null);
        Path downloaded = tempDir.resolve("catalog-random-9371.inpx");
        Files.write(downloaded, new byte[]{1, 2, 3});
        String inpxUrl = "https://example.test/catalog.inpx?token=rotates";
        ImportResult result = mock(ImportResult.class);

        when(downloader.download(eq(collection), eq(inpxUrl), any(), any())).thenReturn(downloaded);
        when(importer.execute(any(ImportContext.class))).thenReturn(result);

        assertThat(useCase.execute(collection, inpxUrl, new AtomicBoolean(false), p -> {})).isSameAs(result);

        ArgumentCaptor<ImportContext> context = ArgumentCaptor.forClass(ImportContext.class);
        verify(importer).execute(context.capture());
        assertThat(context.getValue().getCatalogSourceKey()).isEqualTo("remote-collection:collection-42");
        assertThat(context.getValue().getCatalogSourceLocation()).isEqualTo(inpxUrl);
        assertThat(context.getValue().getCatalogSourceKey()).doesNotContain(downloaded.getFileName().toString());
        verify(lifecycle).rebuildSearchIndex();
    }
}
