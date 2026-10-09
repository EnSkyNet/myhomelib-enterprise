package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.usecase.group.LoadGroupUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.service.HelpTopicRegistry;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class WorkspaceManagerNavigationStateTest {

    @Test
    void exposesBackForwardStateWithoutMainControllerCallbacks() {
        FxmlLoaderFactory loader = mock(FxmlLoaderFactory.class);
        LocalizationService localization = mock(LocalizationService.class);
        BookLoaderService bookLoader = mock(BookLoaderService.class);
        BookDownloadCoordinator bookDownloadCoordinator = mock(BookDownloadCoordinator.class);
        HelpTopicRegistry help = mock(HelpTopicRegistry.class);
        LoadGroupUseCase groups = mock(LoadGroupUseCase.class);
        SessionService session = mock(SessionService.class);
        when(loader.loadWorkspace("/view/dashboard.fxml")).thenAnswer(inv -> new Pane());
        when(loader.loadSearchWorkspace("alpha")).thenAnswer(inv -> new Pane());

        WorkspaceManager manager = new WorkspaceManager(
                loader, localization, bookLoader, bookDownloadCoordinator, help, groups, session, mock(ApplicationState.class));
        manager.init(new StackPane());

        manager.showDashboard();
        assertThat(manager.canGoBackProperty().get()).isFalse();
        assertThat(manager.canGoForwardProperty().get()).isFalse();

        manager.showSearchResults("alpha");
        assertThat(manager.canGoBack()).isTrue();
        assertThat(manager.canGoBackProperty().get()).isTrue();
        assertThat(manager.canGoForwardProperty().get()).isFalse();

        manager.goBack();
        assertThat(manager.canGoBackProperty().get()).isFalse();
        assertThat(manager.canGoForwardProperty().get()).isTrue();

        manager.goForward();
        assertThat(manager.canGoBackProperty().get()).isTrue();
        assertThat(manager.canGoForwardProperty().get()).isFalse();
    }
    @Test
    void backForwardRestoresExactSequenceWithoutPushingRestoredEntriesAgain() {
        FxmlLoaderFactory loader = mock(FxmlLoaderFactory.class);
        LocalizationService localization = mock(LocalizationService.class);
        BookLoaderService bookLoader = mock(BookLoaderService.class);
        BookDownloadCoordinator bookDownloadCoordinator = mock(BookDownloadCoordinator.class);
        HelpTopicRegistry help = mock(HelpTopicRegistry.class);
        LoadGroupUseCase groups = mock(LoadGroupUseCase.class);
        SessionService session = mock(SessionService.class);
        when(loader.loadWorkspace("/view/dashboard.fxml")).thenAnswer(inv -> new Pane());
        when(loader.loadSearchWorkspace(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> new Pane());

        WorkspaceManager manager = new WorkspaceManager(
                loader, localization, bookLoader, bookDownloadCoordinator, help, groups, session, mock(ApplicationState.class));
        manager.init(new StackPane());

        manager.showDashboard();
        manager.showSearchResults("B");
        manager.showSearchResults("C");
        assertThat(manager.canGoBack()).isTrue();
        assertThat(manager.canGoForward()).isFalse();

        manager.goBack();
        manager.goBack();
        assertThat(manager.canGoBack()).isFalse();
        assertThat(manager.canGoForward()).isTrue();

        manager.goForward();
        manager.goForward();
        assertThat(manager.canGoBack()).isTrue();
        assertThat(manager.canGoForward()).isFalse();

        org.mockito.Mockito.verify(loader, org.mockito.Mockito.times(2)).loadSearchWorkspace("C");
        org.mockito.Mockito.verify(loader, org.mockito.Mockito.times(3)).loadSearchWorkspace("B");
    }

    @Test
    void sameAuthorCanBeOpenedAgainAfterOperationsWorkspace() {
        FxmlLoaderFactory loader = mock(FxmlLoaderFactory.class);
        LocalizationService localization = mock(LocalizationService.class);
        BookLoaderService bookLoader = mock(BookLoaderService.class);
        BookDownloadCoordinator bookDownloadCoordinator = mock(BookDownloadCoordinator.class);
        HelpTopicRegistry help = mock(HelpTopicRegistry.class);
        LoadGroupUseCase groups = mock(LoadGroupUseCase.class);
        SessionService session = mock(SessionService.class);
        AuthorId authorId = AuthorId.fromString("11111111-1111-1111-1111-111111111111");
        when(loader.loadAuthorWorkspace(authorId)).thenAnswer(inv -> new Pane());
        when(loader.loadWorkspace("/view/operation-center-workspace.fxml")).thenAnswer(inv -> new Pane());

        WorkspaceManager manager = new WorkspaceManager(
                loader, localization, bookLoader, bookDownloadCoordinator, help, groups, session, mock(ApplicationState.class));
        manager.init(new StackPane());

        manager.showAuthorWorkspace(authorId);
        manager.showOperationCenterWorkspace();
        manager.showAuthorWorkspace(authorId);

        verify(loader, times(2)).loadAuthorWorkspace(authorId);
        assertThat(manager.canGoBack()).isTrue();
    }

    @Test
    void sameAuthorCanBeOpenedAgainAfterReaderWorkspaceState() {
        FxmlLoaderFactory loader = mock(FxmlLoaderFactory.class);
        LocalizationService localization = mock(LocalizationService.class);
        BookLoaderService bookLoader = mock(BookLoaderService.class);
        BookDownloadCoordinator bookDownloadCoordinator = mock(BookDownloadCoordinator.class);
        HelpTopicRegistry help = mock(HelpTopicRegistry.class);
        LoadGroupUseCase groups = mock(LoadGroupUseCase.class);
        SessionService session = mock(SessionService.class);
        AuthorId authorId = AuthorId.fromString("22222222-2222-2222-2222-222222222222");
        when(loader.loadAuthorWorkspace(authorId)).thenAnswer(inv -> new Pane());

        WorkspaceManager manager = new WorkspaceManager(
                loader, localization, bookLoader, bookDownloadCoordinator, help, groups, session, mock(ApplicationState.class));
        manager.init(new StackPane());

        manager.showAuthorWorkspace(authorId);
        manager.setWorkspace(new Pane());
        manager.push("new-reader", "33333333-3333-3333-3333-333333333333");
        manager.showAuthorWorkspace(authorId);

        verify(loader, times(2)).loadAuthorWorkspace(authorId);
        assertThat(manager.canGoBack()).isTrue();
    }

}
