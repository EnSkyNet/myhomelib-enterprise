package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.catalog.CatalogSourceState;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.catalog.CatalogSourceStatePort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogPackage;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.application.port.out.download.RemoteDownloadMetadata;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.Sha256Support;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Opt-in probe for the post-download identical-full-snapshot fast path.
 *
 * <p>The supplied file has already been "downloaded": hashing and network transfer are deliberately
 * outside the timed region. The probe measures only production update orchestration after the
 * downloader returns metadata with the already-applied SHA-256 fingerprint.</p>
 */
class RealOnlineNoOpPerformanceProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.noop.inpx", matches = ".+")
    void identicalFullSnapshotReturnsBeforeCheckpointImportAndDerivedState(@TempDir Path tempDir) throws Exception {
        Path inpx = Path.of(System.getProperty("mhl.real.noop.inpx")).toAbsolutePath().normalize();
        assertThat(inpx).isRegularFile();
        String sha256 = Sha256Support.file(inpx);

        RemoteCatalogDownloadPort downloader = mock(RemoteCatalogDownloadPort.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        CatalogSourceStatePort state = mock(CatalogSourceStatePort.class);
        SearchIndexer search = mock(SearchIndexer.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        StatisticsRepository statistics = mock(StatisticsRepository.class);
        CollectionBackupPort backup = mock(CollectionBackupPort.class);
        UpdateCollectionFromNetworkUseCase useCase = new UpdateCollectionFromNetworkUseCase(
                downloader, importer, lifecycle, state, search, books, statistics, backup,
                50_000, new com.myhomelibcorp.application.operation.LibraryOperationCoordinator());

        String collectionId = "real-noop-probe";
        String sourceKey = "remote-collection:" + collectionId;
        Collection collection = new Collection(collectionId, "Online", tempDir, null, 2,
                null, null, "https://example.test/books", null);
        RemoteCatalogPackage pkg = RemoteCatalogPackage.of(
                inpx,
                "https://example.test/flibusta_online_fb2.inpx",
                "20260905",
                true,
                RemoteDownloadMetadata.of("etag-real", "Sat, 05 Sep 2026 00:00:00 GMT",
                        sha256, Files.size(inpx), "inpx"));
        RemoteCatalogUpdatePlan plan = RemoteCatalogUpdatePlan.of(java.util.List.of(pkg), "20260905");

        when(lifecycle.getCurrentCollection()).thenReturn(collection);
        when(state.get(sourceKey)).thenReturn(new CatalogSourceState(
                sourceKey, "", "", "20260904", "", "", "", "", "", ""));
        when(state.matchesAppliedFingerprint(sourceKey, sha256)).thenReturn(true);
        when(downloader.downloadUpdates(eq(collection), anyString(), anyString(),
                any(AtomicBoolean.class), any(DoubleConsumer.class), any(Consumer.class))).thenReturn(plan);

        for (int i = 0; i < 10; i++) {
            ImportResult warm = useCase.execute(collection, "https://example.test/catalog.inpx", null, null);
            assertThat(warm.imported()).isZero();
        }
        clearInvocations(downloader, state);

        int iterations = 100;
        long[] micros = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long started = System.nanoTime();
            ImportResult result = useCase.execute(collection, "https://example.test/catalog.inpx", null, null);
            micros[i] = Math.max(0L, (System.nanoTime() - started) / 1_000L);
            assertThat(result.imported()).isZero();
            assertThat(result.changes().complete()).isTrue();
        }
        Arrays.sort(micros);
        long medianUs = micros[iterations / 2];
        long p95Us = micros[(int) Math.ceil(iterations * 0.95) - 1];
        long maxUs = micros[iterations - 1];

        verifyNoInteractions(importer, search, statistics, backup, books);
        verify(state, never()).recordFailure(anyString(), anyString());
        System.out.printf(
                "REAL_ONLINE_NOOP_RESULT iterations=%d medianUs=%d p95Us=%d maxUs=%d fileBytes=%d sha256=%s%n",
                iterations, medianUs, p95Us, maxUs, Files.size(inpx), sha256);
    }
}
