package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.service.HelpTopicRegistry;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceManagerNavigationStateTest {

    @Test
    void exposesBackForwardStateWithoutMainControllerCallbacks() {
        FxmlLoaderFactory loader = mock(FxmlLoaderFactory.class);
        LocalizationService localization = mock(LocalizationService.class);
        BookLoaderService bookLoader = mock(BookLoaderService.class);
        BookDownloadCoordinator bookDownloadCoordinator = mock(BookDownloadCoordinator.class);
        LoadBookByIdUseCase loadBookByIdUseCase = mock(LoadBookByIdUseCase.class);
        HelpTopicRegistry help = mock(HelpTopicRegistry.class);
        LoadGroupUseCase groups = mock(LoadGroupUseCase.class);
        SessionService session = mock(SessionService.class);
        when(loader.loadWorkspace("/view/dashboard.fxml")).thenAnswer(inv -> new Pane());
        when(loader.loadSearchWorkspace("alpha")).thenAnswer(inv -> new Pane());

        WorkspaceManager manager = new WorkspaceManager(
                loader, localization, bookLoader, bookDownloadCoordinator, loadBookByIdUseCase, help, groups, session);
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
}
