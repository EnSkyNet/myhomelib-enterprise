package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpdsLifecycleIsolationTest {

    @Test
    void catalogQueryHoldsSharedQueryBarrierAcrossProjection() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        OpdsCatalogQueryPort port = mock(OpdsCatalogQueryPort.class);
        when(port.authors(0, 10)).thenAnswer(ignored -> {
            assertThatThrownBy(() -> coordinator.acquire(LibraryOperationType.SWITCH))
                    .isInstanceOf(LibraryOperationConflictException.class);
            return new OpdsPage<OpdsFacetDto>(List.of(), 0, 0, 10);
        });

        OpdsCatalogService service = new OpdsCatalogService(port, coordinator);
        assertThat(service.authors(0, 10).items()).isEmpty();
        assertThat(coordinator.activeOperation()).isNull();
    }

    @Test
    void catalogQueryDoesNotTouchDynamicPortWhileSwitchOwnsCoordinator() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        OpdsCatalogQueryPort port = mock(OpdsCatalogQueryPort.class);
        OpdsCatalogService service = new OpdsCatalogService(port, coordinator);

        try (var ignored = coordinator.acquire(LibraryOperationType.SWITCH)) {
            assertThatThrownBy(() -> service.authors(0, 10))
                    .isInstanceOf(LibraryOperationConflictException.class);
            verifyNoInteractions(port);
        }
    }

    @Test
    void downloadResolveDoesNotStartWhileSwitchOwnsCoordinator() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        OpdsDownloadService service = new OpdsDownloadService(load, resolve, coordinator);

        try (var ignored = coordinator.acquire(LibraryOperationType.SWITCH)) {
            assertThatThrownBy(() -> service.open("11111111-1111-1111-1111-111111111111"))
                    .isInstanceOf(LibraryOperationConflictException.class);
            verifyNoInteractions(load, resolve);
        }
    }


    @Test
    void largeDownloadStreamsFromPinnedHandleWithoutWholeFileMaterialization(@TempDir Path tempDir) throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024 + 17];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        Path source = tempDir.resolve("large.fb2");
        Files.write(source, payload);

        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        when(load.execute(any())).thenReturn(Optional.of(com.myhomelibcorp.application.dto.BookDto.builder()
                .id("11111111-1111-1111-1111-111111111111").title("Large").fileName("large.fb2").local(true).build()));
        when(resolve.execute(any(), any())).thenReturn(new com.myhomelibcorp.application.usecase.book.ResolvedBookContent(source, false));

        OpdsDownloadService service = new OpdsDownloadService(load, resolve);
        try (OpdsDownloadService.Download download = service.open("11111111-1111-1111-1111-111111111111").orElseThrow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            assertThat(download.size()).isEqualTo(payload.length);
            download.transferTo(out);
            assertThat(out.toByteArray()).isEqualTo(payload);
        }
    }

    @Test
    void clientDisconnectDuringDownloadStillReleasesPinnedFileAndTemporaryContent(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("temporary.fb2");
        Files.write(source, new byte[256 * 1024]);

        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        when(load.execute(any())).thenReturn(Optional.of(com.myhomelibcorp.application.dto.BookDto.builder()
                .id("11111111-1111-1111-1111-111111111111").title("Temporary").fileName("temporary.fb2").local(true).build()));
        when(resolve.execute(any(), any())).thenReturn(new com.myhomelibcorp.application.usecase.book.ResolvedBookContent(source, true));

        OpdsDownloadService service = new OpdsDownloadService(load, resolve);
        OpdsDownloadService.Download download = service.open("11111111-1111-1111-1111-111111111111").orElseThrow();
        OutputStream disconnected = new OutputStream() {
            private boolean first = true;
            @Override public void write(int value) throws IOException { throw new IOException("client disconnected"); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (first) { first = false; throw new IOException("client disconnected"); }
                throw new IOException("client disconnected");
            }
        };

        assertThatThrownBy(() -> download.transferTo(disconnected))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("client disconnected");
        download.close();

        assertThat(Files.exists(source)).isFalse();
        assertThatThrownBy(() -> download.transferTo(OutputStream.nullOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("already closed");
    }

    @Test
    void downloadDoesNotMaskRepositoryFailureAsInvalidBookId() {
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        when(load.execute(any())).thenThrow(new IllegalStateException("database unavailable"));
        OpdsDownloadService service = new OpdsDownloadService(load, resolve);

        assertThatThrownBy(() -> service.open("11111111-1111-1111-1111-111111111111"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verifyNoInteractions(resolve);
    }
}
