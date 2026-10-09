package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.MarkSavedSearchUsedUseCase;
import com.myhomelibcorp.application.usecase.search.SetSavedSearchPinnedUseCase;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SavedSearchesActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void onNewSmartCollection_openSmartCollectionDialogReportsLoaderFailure() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(SavedSearchesController.class);
        var factory = f.dependency(FxmlLoaderFactory.class);
        var dialogs = f.dependency(DialogService.class);
        doThrow(new IllegalStateException("fixture loader failure"))
                .when(factory).configureControllerFactory(any(FXMLLoader.class));
        UiActionTestSupport.setField(f.instance(), "savedSearchesListView", UiActionTestSupport.fx(ListView::new));

        UiActionTestSupport.invoke(f.instance(), "onNewSmartCollection");

        verify(dialogs).showError(any(), any());
    }

    @Test
    void activateSelectedMarksUsagePublishesSelectionAndReloadsList() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(SavedSearchesController.class);
        SavedSearch selected = new SavedSearch("Recent", "java", null);
        ListView<SavedSearch> list = UiActionTestSupport.fx(ListView::new);
        UiActionTestSupport.fx(() -> { list.getItems().add(selected); list.getSelectionModel().select(selected); return null; });
        UiActionTestSupport.setField(f.instance(), "savedSearchesListView", list);
        when(f.dependency(MarkSavedSearchUsedUseCase.class).execute(selected.getId())).thenReturn(selected);
        when(f.dependency(LoadSavedSearchesUseCase.class).execute()).thenReturn(List.of(selected));
        AtomicReference<SavedSearch> activated = new AtomicReference<>();
        f.instance().setOnSearchSelected(activated::set);

        UiActionTestSupport.invoke(f.instance(), "activateSelected");

        verify(f.dependency(MarkSavedSearchUsedUseCase.class)).execute(selected.getId());
        assertThat(activated.get()).isSameAs(selected);
    }

    @Test
    void togglePinnedFlipsCurrentPinAndReloadsList() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(SavedSearchesController.class);
        SavedSearch selected = new SavedSearch("Recent", "java", null);
        ListView<SavedSearch> list = UiActionTestSupport.fx(ListView::new);
        UiActionTestSupport.fx(() -> { list.getItems().add(selected); list.getSelectionModel().select(selected); return null; });
        UiActionTestSupport.setField(f.instance(), "savedSearchesListView", list);
        when(f.dependency(LoadSavedSearchesUseCase.class).execute()).thenReturn(List.of(selected));

        UiActionTestSupport.invoke(f.instance(), "togglePinned");

        verify(f.dependency(SetSavedSearchPinnedUseCase.class)).execute(selected.getId(), true);
    }
}
