package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionPropertiesProgrammaticActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void browseUsesFileChooserBoundaryAndUpdatesRootFolderBeforeCancel() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(CollectionPropertiesUiService.class);
        ApplicationState state = fixture.dependency(ApplicationState.class);
        ApplicationSettingsPort settings = fixture.dependency(ApplicationSettingsPort.class);
        FileChooserService chooser = fixture.dependency(FileChooserService.class);
        Path original = Files.createTempDirectory("mhl-collection-original-");
        Path chosen = Files.createTempDirectory("mhl-collection-chosen-");
        Collection collection = new Collection("c1", "Test", original, "library.db", 0, null, null, null, null);
        when(state.getCurrentLibraryCollection()).thenReturn(collection);
        when(settings.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(chooser.chooseDirectory(any(), anyString(), any())).thenReturn(chosen.toFile());

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { fixture.instance().show(null); }
            catch (Throwable t) { error.set(t); }
            finally { done.countDown(); }
        });
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                GridPane grid = (GridPane) pane.getContent();
                Button browse = grid.getChildren().stream().filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow();
                TextField root = grid.getChildren().stream().filter(TextField.class::isInstance).map(TextField.class::cast)
                        .filter(field -> Integer.valueOf(2).equals(GridPane.getRowIndex(field))).findFirst().orElseThrow();
                browse.fire();
                assertThat(root.getText()).isEqualTo(chosen.toString());
                verify(chooser).chooseDirectory(isNull(), anyString(), argThat(file -> file != null && file.toPath().equals(original)));
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
            } catch (Throwable t) { error.set(t); }
        });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private static DialogPane latestDialogPane() {
        List<Window> windows = Window.getWindows().stream().filter(Window::isShowing).toList();
        for (int i = windows.size() - 1; i >= 0; i--) {
            Node root = windows.get(i).getScene() == null ? null : windows.get(i).getScene().getRoot();
            if (root instanceof DialogPane pane) return pane;
        }
        throw new AssertionError("No visible DialogPane");
    }
}
