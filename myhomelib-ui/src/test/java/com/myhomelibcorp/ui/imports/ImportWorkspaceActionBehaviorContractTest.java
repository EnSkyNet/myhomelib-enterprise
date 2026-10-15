package com.myhomelibcorp.ui.imports;

import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;
import com.myhomelibcorp.application.imports.diagnostics.ImportSeverity;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.StatusBarViewModel;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportWorkspaceActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }
    @TempDir Path tempDir;

    @Test
    void onChooseDirectoryAndOnChooseFilePublishChosenPaths() throws Exception {
        var f = fixture();
        FileChooserService chooser = f.dependency(FileChooserService.class);
        Path book = tempDir.resolve("book.fb2");
        java.nio.file.Files.writeString(book, "x");
        when(chooser.chooseDirectory(any(), anyString())).thenReturn(tempDir.toFile());
        when(chooser.chooseFile(any(), anyString(), anyList())).thenReturn(book.toFile());

        UiActionTestSupport.invoke(f.instance(), "onChooseDirectory");
        UiActionTestSupport.invoke(f.instance(), "onChooseFile");

        assertThat(UiActionTestSupport.fx(() -> ((TextField) UiActionTestSupport.getField(f.instance(), "directoryField")).getText()))
                .isEqualTo(tempDir.toFile().getAbsolutePath());
        assertThat(UiActionTestSupport.fx(() -> ((TextField) UiActionTestSupport.getField(f.instance(), "fileField")).getText()))
                .isEqualTo(book.toFile().getAbsolutePath());
    }

    @Test
    void onImportDirectoryAndOnImportFileRejectMissingInputBeforeBackgroundWork() throws Exception {
        var f = fixture();
        DialogService dialogs = f.dependency(DialogService.class);

        UiActionTestSupport.invoke(f.instance(), "onImportDirectory");
        UiActionTestSupport.invoke(f.instance(), "onImportFile");

        verify(dialogs, times(2)).showError(anyString(), anyString());
        verifyNoInteractions(f.dependency(com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase.class));
        verifyNoInteractions(f.dependency(com.myhomelibcorp.application.usecase.imports.ImportFileUseCase.class));
    }

    @Test
    void onCancelMarksRunningImportCancelledAndDisablesCancelButton() throws Exception {
        var f = fixture();
        Button cancel = (Button) UiActionTestSupport.getField(f.instance(), "cancelButton");
        UiActionTestSupport.setField(f.instance(), "importRunning", true);

        UiActionTestSupport.invoke(f.instance(), "onCancel");

        assertThat(((java.util.concurrent.atomic.AtomicBoolean) UiActionTestSupport.getField(f.instance(), "cancelFlag")).get()).isTrue();
        assertThat(UiActionTestSupport.fx(cancel::isDisabled)).isTrue();
    }

    @Test
    void diagnosticsFormatterDoesNotExposeDiagnosticContextPaths() throws Exception {
        var f = fixture();
        ImportIssue issue = new ImportIssue(ImportSeverity.ERROR, "import", "FILE_IMPORT_FAILED",
                "broken.fb2", "failed at /private/user/library/broken.fb2", false,
                Map.of("exceptionType", "IOException", "path", "/private/user/library/broken.fb2"));
        ImportIssue externalMessage = new ImportIssue(ImportSeverity.WARNING, "validate", "EXTERNAL_WARNING",
                "record-2", "Invalid source data at /private/user/library/secret.txt", false, Map.of());
        when(f.dependency(LocalizationService.class).format(
                eq("ui.import.diagnostics.file_failed"), eq("broken.fb2"), eq("IOException")))
                .thenReturn("Could not import file broken.fb2");
        Method formatter = ImportWorkspaceController.class.getDeclaredMethod("formatImportIssues", List.class);
        formatter.setAccessible(true);

        String formatted = (String) formatter.invoke(f.instance(), List.of(issue, externalMessage));

        assertThat(formatted).contains("FILE_IMPORT_FAILED", "broken.fb2", "EXTERNAL_WARNING");
        assertThat(formatted).doesNotContain("/private/user/library", "secret.txt");
    }

    @Test
    void onSettingsRejectsInvalidBatchSizeWithVisibleError() throws Exception {
        var f = fixture();
        DialogService dialogs = f.dependency(DialogService.class);
        Method action = ImportWorkspaceController.class.getDeclaredMethod("onSettings");
        action.setAccessible(true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try { action.invoke(f.instance()); }
            catch (Throwable t) { failure.set(t.getCause() == null ? t : t.getCause()); }
            finally { done.countDown(); }
        });
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                TextField input = descendants(pane).stream().filter(TextField.class::isInstance).map(TextField.class::cast).findFirst().orElseThrow();
                input.setText("not-a-number");
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            } catch (Throwable t) { failure.set(t); }
        });

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
        verify(dialogs).showError(anyString(), anyString());
    }

    private static UiActionTestSupport.Fixture<ImportWorkspaceController> fixture() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(ImportWorkspaceController.class);
        when(f.dependency(LocalizationService.class).text(anyString())).thenAnswer(i -> i.getArgument(0));
        when(f.dependency(ApplicationState.class).getStatusBar()).thenReturn(new StatusBarViewModel());
        when(f.dependency(LocalizationService.class).format(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        UiActionTestSupport.setField(f.instance(), "directoryField", UiActionTestSupport.fx(TextField::new));
        UiActionTestSupport.setField(f.instance(), "fileField", UiActionTestSupport.fx(TextField::new));
        UiActionTestSupport.setField(f.instance(), "statusLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(f.instance(), "cancelButton", UiActionTestSupport.fx(Button::new));
        return f;
    }

    private static DialogPane latestDialogPane() {
        return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene)
                .filter(java.util.Objects::nonNull).map(javafx.scene.Scene::getRoot)
                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).reduce((a,b)->b).orElseThrow();
    }
    private static java.util.List<Node> descendants(javafx.scene.Parent parent) {
        java.util.ArrayList<Node> out = new java.util.ArrayList<>();
        for (Node child : parent.getChildrenUnmodifiable()) { out.add(child); if (child instanceof javafx.scene.Parent p) out.addAll(descendants(p)); }
        return out;
    }
}
