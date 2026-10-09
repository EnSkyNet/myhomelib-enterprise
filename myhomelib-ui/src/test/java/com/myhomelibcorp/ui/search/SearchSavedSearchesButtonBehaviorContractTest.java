package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.ui.controller.SavedSearchesController;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SearchSavedSearchesButtonBehaviorContractTest {
    @BeforeAll
    static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void savedSearchesButtonOpensSavedSearchesDialogAndWiresSelectionCallback() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(SearchWorkspaceController.class);
        FxmlLoaderFactory factory = fixture.dependency(FxmlLoaderFactory.class);
        LocalizationService i18n = fixture.dependency(LocalizationService.class);
        SavedSearchesController savedController = mock(SavedSearchesController.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            FXMLLoader loader = inv.getArgument(0);
            loader.setControllerFactory(type -> type == SavedSearchesController.class ? savedController : mock(type));
            return null;
        }).when(factory).configureControllerFactory(any(FXMLLoader.class));

        TextField searchField = UiActionTestSupport.fx(TextField::new);
        Button saveSearchButton = UiActionTestSupport.fx(Button::new);
        Button savedSearchesButton = UiActionTestSupport.fx(Button::new);
        UiActionTestSupport.setField(fixture.instance(), "searchField", searchField);
        UiActionTestSupport.setField(fixture.instance(), "saveSearchButton", saveSearchButton);
        UiActionTestSupport.setField(fixture.instance(), "savedSearchesButton", savedSearchesButton);
        Stage owner = UiActionTestSupport.fx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(searchField, saveSearchButton, savedSearchesButton)));
            stage.show();
            return stage;
        });

        UiActionTestSupport.invoke(fixture.instance(), "setupButtons");
        UiActionTestSupport.fx(() -> { savedSearchesButton.fire(); return null; });

        List<Window> opened = UiActionTestSupport.fx(() -> Window.getWindows().stream()
                .filter(Window::isShowing).filter(window -> window != owner).toList());
        assertThat(opened).hasSize(1);
        verify(savedController).setOnSearchSelected(any());

        UiActionTestSupport.fx(() -> { opened.forEach(Window::hide); owner.hide(); return null; });
    }
}
