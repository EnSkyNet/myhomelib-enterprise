package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import org.junit.jupiter.api.Test;

import java.util.List;

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
