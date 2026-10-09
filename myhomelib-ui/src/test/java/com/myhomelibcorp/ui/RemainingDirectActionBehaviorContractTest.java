package com.myhomelibcorp.ui;

import com.myhomelibcorp.application.navigation.NavigationMode;
import com.myhomelibcorp.application.navigation.NavigationQueryService;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.ui.controller.StatisticsController;
import com.myhomelibcorp.ui.following.FollowedAuthorsWorkspaceController;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.table.TreeBookTableController;
import com.myhomelibcorp.ui.updates.UpdatesWorkspaceController;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.scene.control.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RemainingDirectActionBehaviorContractTest {
    @BeforeAll
    static void startFx() throws Exception {
        UiActionTestSupport.startFx();
    }

    @Test
    void FollowedAuthorsWorkspaceController_refreshStartsAQueryAndShowsBusyState() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(FollowedAuthorsWorkspaceController.class);
        var controller = f.instance();
        var executor = f.dependency(UiBackgroundExecutor.class);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        doReturn(pending).when(executor).submitCollectionScoped(eq(LibraryOperationType.QUERY), any(Callable.class));

        TableView<Object> table = UiActionTestSupport.fx(TableView::new);
        ProgressIndicator progress = UiActionTestSupport.fx(ProgressIndicator::new);
        Button refreshButton = UiActionTestSupport.fx(Button::new);
        UiActionTestSupport.setField(controller, "followedTable", table);
        UiActionTestSupport.setField(controller, "detailLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "progressIndicator", progress);
        UiActionTestSupport.setField(controller, "refreshButton", refreshButton);
        UiActionTestSupport.setField(controller, "openAuthorButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(controller, "acknowledgeButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(controller, "unfollowButton", UiActionTestSupport.fx(Button::new));

        UiActionTestSupport.invoke(controller, "refresh");

        verify(executor).submitCollectionScoped(eq(LibraryOperationType.QUERY), any(Callable.class));
        assertThat(UiActionTestSupport.fx(progress::isVisible)).isTrue();
        assertThat(UiActionTestSupport.fx(refreshButton::isDisabled)).isTrue();
    }

    @Test
    void NavigationPanelController_onClearListSearchClearsTheVisibleQuery() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(NavigationPanelController.class);
        TextField search = UiActionTestSupport.fx(() -> new TextField("Asimov"));
        UiActionTestSupport.setField(f.instance(), "listSearchField", search);

        UiActionTestSupport.invoke(f.instance(), "onClearListSearch");

        String cleared = UiActionTestSupport.fx(search::getText);
        assertThat(cleared).isEmpty();
    }

    @Test
    void NavigationPanelController_onLoadMoreAuthorsUsesCurrentKeysetCursor() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(NavigationPanelController.class);
        var query = f.dependency(NavigationQueryService.class);
        var cursor = new NavigationQueryService.AuthorCursor("Asimov", "Isaac", "", "author-1");
        CompletableFuture<NavigationQueryService.AuthorPage> pending = new CompletableFuture<>();
        when(query.loadAuthorsAfter(eq('A'), eq(500), same(cursor))).thenReturn(pending);

        UiActionTestSupport.setField(f.instance(), "navigationListView", UiActionTestSupport.fx(ListView::new));
        UiActionTestSupport.setField(f.instance(), "loadMoreAuthorsButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(f.instance(), "currentMode", NavigationMode.AUTHORS);
        UiActionTestSupport.setField(f.instance(), "currentLetter", 'A');
        UiActionTestSupport.setField(f.instance(), "authorCursor", cursor);

        UiActionTestSupport.invoke(f.instance(), "onLoadMoreAuthors");

        verify(query).loadAuthorsAfter('A', 500, cursor);
        assertThat((boolean) UiActionTestSupport.getField(f.instance(), "loadingMoreAuthors")).isTrue();
    }

    @Test
    void StatisticsController_onRefreshUsesEditScopeForForcedRefresh() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(StatisticsController.class);
        var executor = f.dependency(UiBackgroundExecutor.class);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        doReturn(pending).when(executor).submitCollectionScoped(eq(LibraryOperationType.EDIT), any(Callable.class));
        for (String field : List.of("booksCount", "authorsCount", "seriesCount", "genresCount", "languagesCount",
                "publishersCount", "totalSize", "duplicatesCount", "missingCoversCount", "localBooksCount",
                "remoteBooksCount", "readBooksCount", "unreadBooksCount", "favoritesCount", "deletedBooksCount",
                "sourcesCount")) {
            UiActionTestSupport.setField(f.instance(), field, UiActionTestSupport.fx(Label::new));
        }

        UiActionTestSupport.invoke(f.instance(), "onRefresh");

        verify(executor).submitCollectionScoped(eq(LibraryOperationType.EDIT), any(Callable.class));
    }

    @Test
    void UpdatesWorkspaceController_refreshStartsAQueryAndDisablesRefresh() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(UpdatesWorkspaceController.class);
        var executor = f.dependency(UiBackgroundExecutor.class);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        doReturn(pending).when(executor).submitCollectionScoped(eq(LibraryOperationType.QUERY), any(Callable.class));

        TreeView<Object> tree = UiActionTestSupport.fx(TreeView::new);
        ProgressIndicator progress = UiActionTestSupport.fx(ProgressIndicator::new);
        Button refresh = UiActionTestSupport.fx(Button::new);
        UiActionTestSupport.setField(f.instance(), "updatesTree", tree);
        UiActionTestSupport.setField(f.instance(), "summaryLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(f.instance(), "emptyLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(f.instance(), "detailLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(f.instance(), "progressIndicator", progress);
        UiActionTestSupport.setField(f.instance(), "openAuthorButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(f.instance(), "openBookButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(f.instance(), "downloadButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(f.instance(), "acknowledgeButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(f.instance(), "acknowledgeAllButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(f.instance(), "refreshButton", refresh);

        UiActionTestSupport.invoke(f.instance(), "refresh");

        verify(executor).submitCollectionScoped(eq(LibraryOperationType.QUERY), any(Callable.class));
        assertThat(UiActionTestSupport.fx(progress::isVisible)).isTrue();
        assertThat(UiActionTestSupport.fx(refresh::isDisabled)).isTrue();
    }

    @Test
    void TreeBookTableController_checkBoxActionUpdatesBoundBookSelection() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(TreeBookTableController.class);
        Method createSelectionCell = TreeBookTableController.class.getDeclaredMethod("createSelectionCell");
        createSelectionCell.setAccessible(true);
        @SuppressWarnings("unchecked") javafx.scene.control.TreeTableCell<BookViewModel, Boolean> cell =
                UiActionTestSupport.fx(() -> (javafx.scene.control.TreeTableCell<BookViewModel, Boolean>) createSelectionCell.invoke(f.instance()));
        BookViewModel book = new BookViewModel(); book.setId("1"); book.setTitle("Book");
        java.lang.reflect.Field boundBook = cell.getClass().getDeclaredField("boundBook"); boundBook.setAccessible(true); boundBook.set(cell, book);
        java.lang.reflect.Field checkBoxField = cell.getClass().getDeclaredField("checkBox"); checkBoxField.setAccessible(true);
        javafx.scene.control.CheckBox checkBox = (javafx.scene.control.CheckBox) checkBoxField.get(cell);

        UiActionTestSupport.fx(() -> { checkBox.setSelected(true); checkBox.fireEvent(new javafx.event.ActionEvent()); return null; });

        assertThat(book.isSelected()).isTrue();
    }

    @Test
    void TreeBookTableController_selectAllBooksMutatesCanonicalBookSelectionBothWays() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(TreeBookTableController.class);
        var selection = f.dependency(BookSelectionService.class);
        TreeTableView<BookViewModel> table = UiActionTestSupport.fx(TreeTableView::new);
        BookViewModel book = new BookViewModel();
        book.setId("book-1");
        book.setTitle("Book");
        TreeItem<BookViewModel> root = new TreeItem<>();
        root.getChildren().add(new TreeItem<>(book));
        UiActionTestSupport.fx(() -> { table.setRoot(root); return null; });
        UiActionTestSupport.setField(f.instance(), "treeTableView", table);

        Method selectAllBooks = TreeBookTableController.class.getDeclaredMethod("selectAllBooks", boolean.class);
        selectAllBooks.setAccessible(true);
        UiActionTestSupport.fx(() -> { selectAllBooks.invoke(f.instance(), true); return null; });
        verify(selection).setSelected(org.mockito.ArgumentMatchers.<java.util.Collection<BookViewModel>>argThat(books -> books.size() == 1 && "book-1".equals(books.iterator().next().getId())), eq(true));

        UiActionTestSupport.fx(() -> { selectAllBooks.invoke(f.instance(), false); return null; });
        verify(selection).setSelected(org.mockito.ArgumentMatchers.<java.util.Collection<BookViewModel>>argThat(books -> books.size() == 1 && "book-1".equals(books.iterator().next().getId())), eq(false));
    }
}
