package com.myhomelibcorp.ui;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.ui.book.BookWorkspaceController;
import com.myhomelibcorp.ui.controller.IntegrityCheckController;
import com.myhomelibcorp.ui.controller.SavedSearchesController;
import com.myhomelibcorp.ui.controller.SmartCollectionDialogController;
import com.myhomelibcorp.ui.controller.StatisticsController;
import com.myhomelibcorp.ui.reader.SearchDialogController;
import com.myhomelibcorp.ui.reader.TOCDialogController;
import com.myhomelibcorp.ui.following.FollowedAuthorsWorkspaceController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.reader.NewReaderWorkspaceController;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.statusbar.StatusBarController;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.myhomelibcorp.ui.support.UiActionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Action-specific behavioral coverage for small FXML handlers that delegate to one owned boundary. */
class RemainingFxmlActionBehaviorContractTest {
    @BeforeAll static void fxStart() throws Exception { startFx(); }

    @Test
    void bookWorkspaceOnOpenOnReadOnOpenFolderAndOnBackDelegateToNavigationBoundary() throws Exception {
        var f = constructWithMocks(BookWorkspaceController.class);
        BookDto book = mock(BookDto.class);
        setField(f.instance(), "currentBook", book);
        NavigationService navigation = f.dependency(NavigationService.class);

        invoke(f.instance(), "onOpen");
        invoke(f.instance(), "onRead");
        invoke(f.instance(), "onOpenFolder");
        invoke(f.instance(), "onBack");

        verify(navigation).openBookFile(book);
        verify(navigation).readBook(book);
        verify(navigation).openBookFolder(book);
        verify(navigation).navigateToAllBooks();
    }

    @Test
    void followedAuthorsOpenUpdatesDelegatesToUpdatesWorkspace() throws Exception {
        var f = constructWithMocks(FollowedAuthorsWorkspaceController.class);
        invoke(f.instance(), "openUpdates");
        verify(f.dependency(NavigationService.class)).navigateToUpdates();
    }

    @Test
    void statusBarOnOperationsClickOpensOperationCenter() throws Exception {
        var f = constructWithMocks(StatusBarController.class);
        invoke(f.instance(), "onOperationsClick");
        verify(f.dependency(WorkspaceManager.class)).showOperationCenterWorkspace();
    }

    @Test
    void readerCloseAnnotationSidebarHidesAndUnmanagesSidebar() throws Exception {
        var f = constructWithMocks(NewReaderWorkspaceController.class);
        VBox sidebar = fx(VBox::new);
        fx(() -> { sidebar.setVisible(true); sidebar.setManaged(true); return null; });
        setField(f.instance(), "annotationSidebar", sidebar);

        invoke(f.instance(), "closeAnnotationSidebar");

        assertThat(fx(sidebar::isVisible)).isFalse();
        assertThat(fx(sidebar::isManaged)).isFalse();
    }
    @Test
    void savedSearchesOnCloseClosesOwningStage() throws Exception {
        var f = constructWithMocks(SavedSearchesController.class);
        ListView<Object> list = fx(ListView::new);
        Stage stage = fx(() -> { Stage s = new Stage(); s.setScene(new Scene(list)); s.show(); return s; });
        setField(f.instance(), "savedSearchesListView", list);
        invoke(f.instance(), "onClose");
        assertThat(fx(stage::isShowing)).isFalse();
    }

    @Test
    void statisticsOnCloseClosesOwningStage() throws Exception {
        var f = constructWithMocks(StatisticsController.class);
        Label anchor = fx(Label::new);
        Stage stage = fx(() -> { Stage s = new Stage(); s.setScene(new Scene(new VBox(anchor))); s.show(); return s; });
        setField(f.instance(), "booksCount", anchor);
        invoke(f.instance(), "onClose");
        assertThat(fx(stage::isShowing)).isFalse();
    }

    @Test
    void searchDialogOnCloseCancelsGenerationAndClosesStage() throws Exception {
        var f = constructWithMocks(SearchDialogController.class);
        TextField anchor = fx(TextField::new);
        Stage stage = fx(() -> { Stage s = new Stage(); s.setScene(new Scene(new VBox(anchor))); s.show(); return s; });
        setField(f.instance(), "searchField", anchor);
        invoke(f.instance(), "onClose");
        assertThat(fx(stage::isShowing)).isFalse();
    }

    @Test
    void tocDialogOnCloseClosesStage() throws Exception {
        var f = constructWithMocks(TOCDialogController.class);
        ListView<Object> anchor = fx(ListView::new);
        Stage stage = fx(() -> { Stage s = new Stage(); s.setScene(new Scene(anchor)); s.show(); return s; });
        setField(f.instance(), "tocListView", anchor);
        invoke(f.instance(), "onClose");
        assertThat(fx(stage::isShowing)).isFalse();
    }

    @Test
    void integrityCheckCloseDialogClosesStage() throws Exception {
        var f = constructWithMocks(IntegrityCheckController.class);
        TextArea anchor = fx(TextArea::new);
        Stage stage = fx(() -> { Stage s = new Stage(); s.setScene(new Scene(anchor)); s.show(); return s; });
        setField(f.instance(), "detailArea", anchor);
        invoke(f.instance(), "closeDialog");
        assertThat(fx(stage::isShowing)).isFalse();
    }

    @Test
    void smartCollectionOnCancelClosesDialogStage() throws Exception {
        var f = constructWithMocks(SmartCollectionDialogController.class);
        VBox rules = fx(VBox::new);
        Stage stage = fx(() -> { Stage s = new Stage(); s.setScene(new Scene(rules)); s.show(); return s; });
        setField(f.instance(), "rulesBox", rules);
        invoke(f.instance(), "onCancel");
        assertThat(fx(stage::isShowing)).isFalse();
    }

}
