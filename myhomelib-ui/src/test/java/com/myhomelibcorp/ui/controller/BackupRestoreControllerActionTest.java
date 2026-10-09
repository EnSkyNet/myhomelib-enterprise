package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.service.BackupRestoreService;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackupRestoreControllerActionTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        if (!started.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("JavaFX did not start");
        Platform.setImplicitExit(false);
    }

    @Test
    void backupChooserUsesTestableChooserBoundaryAndWritesSelection() throws Exception {
        FileChooserService chooser = mock(FileChooserService.class);
        File selected = new File(System.getProperty("java.io.tmpdir"), "mhl-backup-target");
        when(chooser.chooseDirectory(any(), anyString(), any())).thenReturn(selected);
        TextField path = fx(TextField::new);
        BackupController controller = backupController(chooser, mock(DialogService.class));
        set(controller, "backupPathField", path);

        fx(() -> { controller.onSelectPath(); return null; });

        verify(chooser).chooseDirectory(any(), anyString(), any());
        org.junit.jupiter.api.Assertions.assertEquals(selected.getAbsolutePath(), path.getText());
    }

    @Test
    void backupRejectsBlankTargetWithoutStartingWork() throws Exception {
        DialogService dialogs = mock(DialogService.class);
        UiBackgroundExecutor executor = mock(UiBackgroundExecutor.class);
        BackupController controller = new BackupController(
                mock(BackupRestoreService.class), dialogs, mock(FileChooserService.class), executor,
                mock(OperationCenterService.class), mock(ApplicationState.class), localization());
        TextField path = fx(TextField::new);
        path.setText("   ");
        set(controller, "backupPathField", path);

        controller.onBackup();

        verify(dialogs).showError(anyString(), anyString());
        verifyNoInteractions(executor);
    }

    @Test
    void backupCloseClosesOwningStage() {
        BackupController controller = backupController(mock(FileChooserService.class), mock(DialogService.class));
        Stage stage = mock(Stage.class);
        controller.setStage(stage);
        controller.onClose();
        verify(stage).close();
    }

    @Test
    void restoreChooserUsesTestableChooserBoundaryAndWritesSelection() throws Exception {
        FileChooserService chooser = mock(FileChooserService.class);
        File selected = new File(System.getProperty("java.io.tmpdir"), "mhl-missing-backup");
        when(chooser.chooseDirectory(any(), anyString(), any())).thenReturn(selected);
        RestoreController controller = restoreController(chooser, mock(DialogService.class));
        TextField path = fx(TextField::new);
        set(controller, "backupPathField", path);
        set(controller, "logArea", fx(javafx.scene.control.TextArea::new));
        set(controller, "restoreButton", fx(javafx.scene.control.Button::new));
        set(controller, "restoreDatabaseCheckBox", fx(javafx.scene.control.CheckBox::new));
        set(controller, "statusLabel", fx(javafx.scene.control.Label::new));

        fx(() -> { controller.onSelectPath(); return null; });

        verify(chooser).chooseDirectory(any(), anyString(), any());
        org.junit.jupiter.api.Assertions.assertEquals(selected.getAbsolutePath(), path.getText());
    }

    @Test
    void restoreRejectsBlankTargetWithoutStartingWork() throws Exception {
        DialogService dialogs = mock(DialogService.class);
        UiBackgroundExecutor executor = mock(UiBackgroundExecutor.class);
        RestoreController controller = new RestoreController(
                mock(BackupRestoreService.class), dialogs, mock(FileChooserService.class), executor,
                mock(OperationCenterService.class), mock(ApplicationState.class));
        TextField path = fx(TextField::new);
        path.setText(" ");
        set(controller, "backupPathField", path);

        controller.onRestore();

        verify(dialogs).showError(anyString(), anyString());
        verifyNoInteractions(executor);
    }

    @Test
    void restoreCloseClosesOwningStage() {
        RestoreController controller = restoreController(mock(FileChooserService.class), mock(DialogService.class));
        Stage stage = mock(Stage.class);
        controller.setStage(stage);
        controller.onClose();
        verify(stage).close();
    }

    private static BackupController backupController(FileChooserService chooser, DialogService dialogs) {
        return new BackupController(mock(BackupRestoreService.class), dialogs, chooser,
                mock(UiBackgroundExecutor.class), mock(OperationCenterService.class),
                mock(ApplicationState.class), localization());
    }

    private static RestoreController restoreController(FileChooserService chooser, DialogService dialogs) {
        return new RestoreController(mock(BackupRestoreService.class), dialogs, chooser,
                mock(UiBackgroundExecutor.class), mock(OperationCenterService.class), mock(ApplicationState.class));
    }

    private static LocalizationService localization() {
        LocalizationService service = mock(LocalizationService.class);
        when(service.text(anyString())).thenAnswer(i -> i.getArgument(0));
        when(service.format(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        return service;
    }

    private static <T> T fx(java.util.concurrent.Callable<T> call) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(call.call()); } catch (Throwable t) { error.set(t); } finally { done.countDown(); }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("FX task timed out");
        if (error.get() != null) {
            if (error.get() instanceof Exception e) throw e;
            throw new RuntimeException(error.get());
        }
        return result.get();
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
