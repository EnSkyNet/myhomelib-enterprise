package com.myhomelibcorp.ui.updates;

import com.myhomelibcorp.application.catalog.*;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.scene.control.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UpdatesWorkspaceActionBehaviorContractTest {
    private static final String AUTHOR_ID = "11111111-1111-1111-1111-111111111111";
    private static final String BOOK_ID = "22222222-2222-2222-2222-222222222222";
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void openAuthorAndOpenBookNavigateFromSelectedBookUpdate() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(UpdatesWorkspaceController.class);
        UpdatesWorkspaceController c = f.instance();
        when(f.dependency(LocalizationService.class).text(anyString())).thenAnswer(i -> i.getArgument(0));
        TreeView<Object> tree = UiActionTestSupport.fx(TreeView::new);
        UiActionTestSupport.setField(c, "updatesTree", tree);
        UiActionTestSupport.setField(c, "summaryLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(c, "emptyLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(c, "detailLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(c, "progressIndicator", UiActionTestSupport.fx(ProgressIndicator::new));
        for (String field : new String[]{"openAuthorButton","openBookButton","downloadButton","acknowledgeButton","acknowledgeAllButton","refreshButton"})
            UiActionTestSupport.setField(c, field, UiActionTestSupport.fx(Button::new));
        CatalogUpdateItem item = new CatalogUpdateItem(BOOK_ID, "Book", AUTHOR_ID, "Author",
                CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR, false, "2026-09-29");
        CatalogUpdateSnapshot snapshot = new CatalogUpdateSnapshot(1, 1, 0,
                List.of(new CatalogUpdateAuthorGroup(AUTHOR_ID, "Author", List.of(item), List.of())));
        Method apply = UpdatesWorkspaceController.class.getDeclaredMethod("applySnapshot", CatalogUpdateSnapshot.class);
        apply.setAccessible(true);
        UiActionTestSupport.fx(() -> { apply.invoke(c, snapshot); return null; });
        @SuppressWarnings("unchecked") TreeView<Object> actualTree = (TreeView<Object>) UiActionTestSupport.getField(c, "updatesTree");
        UiActionTestSupport.fx(() -> {
            TreeItem<Object> book = actualTree.getRoot().getChildren().get(0).getChildren().get(0).getChildren().get(0);
            actualTree.getSelectionModel().select(book);
            return null;
        });

        UiActionTestSupport.invoke(c, "openAuthor");
        UiActionTestSupport.invoke(c, "openBook");

        verify(f.dependency(NavigationService.class)).navigateToAuthor(AuthorId.fromString(AUTHOR_ID));
        verify(f.dependency(NavigationService.class)).navigateToBook(BookId.fromString(BOOK_ID));
    }
}
