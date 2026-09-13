package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookArtifactDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultNavigationServiceArtifactTest {

    @Test
    void openAsUsesSelectedLocalArtifactWithoutInvokingPreferredDownloadGuard() throws Exception {
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        SeriesRepository series = mock(SeriesRepository.class);
        NavigationPanelController panel = mock(NavigationPanelController.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        BookDownloadCoordinator downloads = mock(BookDownloadCoordinator.class);
        ExternalBookLauncher launcher = mock(ExternalBookLauncher.class);
        DefaultNavigationService service = new DefaultNavigationService(workspace, series, panel, resources, downloads, launcher);

        BookDto book = BookDto.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .title("Multi")
                .fileName("preferred.epub")
                .folder("epub")
                .collectionRoot("/library")
                .preferredArtifactId("epub")
                .build();
        BookArtifactDto selected = BookArtifactDto.builder()
                .id("pdf")
                .format("pdf")
                .fileName("selected.pdf")
                .folder("pdf")
                .collectionRoot("/library")
                .fileSize(2048)
                .local(true)
                .state("AVAILABLE")
                .build();

        service.openBookArtifact(book, selected);

        ArgumentCaptor<BookDto> opened = ArgumentCaptor.forClass(BookDto.class);
        verify(launcher).open(opened.capture());
        verifyNoInteractions(downloads);
        assertThat(opened.getValue().getFileName()).isEqualTo("selected.pdf");
        assertThat(opened.getValue().getFolder()).isEqualTo("pdf");
        assertThat(opened.getValue().getPreferredArtifactId()).isEqualTo("epub");
        assertThat(book.getFileName()).isEqualTo("preferred.epub");
    }

    @Test
    void unavailableArtifactIsClearlyNonOpenableAtNavigationBoundary() throws Exception {
        ExternalBookLauncher launcher = mock(ExternalBookLauncher.class);
        DefaultNavigationService service = new DefaultNavigationService(
                mock(WorkspaceManager.class), mock(SeriesRepository.class), mock(NavigationPanelController.class),
                mock(BookResourcePort.class), mock(BookDownloadCoordinator.class), launcher);
        BookDto book = BookDto.builder().id("22222222-2222-2222-2222-222222222222").title("Remote").build();
        BookArtifactDto remote = BookArtifactDto.builder()
                .id("remote").fileName("remote.fb2").local(false).state("REMOTE_ONLY").build();

        service.openBookArtifact(book, remote);

        verifyNoInteractions(launcher);
    }
}
