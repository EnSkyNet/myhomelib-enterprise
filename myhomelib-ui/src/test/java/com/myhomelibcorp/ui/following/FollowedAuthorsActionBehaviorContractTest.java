package com.myhomelibcorp.ui.following;

import com.myhomelibcorp.application.catalog.CatalogUpdateService;
import com.myhomelibcorp.application.catalog.FollowedAuthorSummary;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.service.*;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowedAuthorsActionBehaviorContractTest {
    private static final String AUTHOR_ID = "11111111-1111-1111-1111-111111111111";
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void openAuthorAcknowledgeAuthorAndUnfollowAuthorUseSelectedAuthorContract() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(FollowedAuthorsWorkspaceController.class);
        FollowedAuthorsWorkspaceController c = f.instance();
        FollowedAuthorSummary row = new FollowedAuthorSummary(AUTHOR_ID, "Author", 5, 2, "Book", "2026-09-29", "2026-09-01");
        TableView<FollowedAuthorSummary> table = UiActionTestSupport.fx(TableView::new);
        UiActionTestSupport.fx(() -> { table.getItems().add(row); table.getSelectionModel().select(row); return null; });
        UiActionTestSupport.setField(c, "followedTable", table);
        UiActionTestSupport.setField(c, "progressIndicator", UiActionTestSupport.fx(ProgressIndicator::new));
        UiActionTestSupport.setField(c, "refreshButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(c, "openAuthorButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(c, "acknowledgeButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(c, "unfollowButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(c, "detailLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(c, "displayedCollectionId", "collection-1");
        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn("collection-1");
        when(f.dependency(ApplicationState.class).getCurrentLibraryCollection()).thenReturn(collection);
        when(f.dependency(LocalizationService.class).text(anyString())).thenAnswer(i -> i.getArgument(0));
        when(f.dependency(DialogService.class).showConfirmation(anyString(), anyString(), anyString())).thenReturn(true);
        when(f.dependency(UiBackgroundExecutor.class).submitCollectionScoped(eq(LibraryOperationType.EDIT), any(Callable.class)))
                .thenAnswer(inv -> { ((Callable<?>) inv.getArgument(1)).call(); return new CompletableFuture<>(); });

        UiActionTestSupport.invoke(c, "openAuthor");
        verify(f.dependency(NavigationService.class)).navigateToAuthor(AuthorId.fromString(AUTHOR_ID));
        UiActionTestSupport.invoke(c, "acknowledgeAuthor");
        verify(f.dependency(CatalogUpdateService.class)).acknowledgeAuthorUpdates(AuthorId.fromString(AUTHOR_ID));
        UiActionTestSupport.invoke(c, "unfollowAuthor");
        verify(f.dependency(CatalogUpdateService.class)).setAuthorFollowed(AuthorId.fromString(AUTHOR_ID), false);
    }
}
