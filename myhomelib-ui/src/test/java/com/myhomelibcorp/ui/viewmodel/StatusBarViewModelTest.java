package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.ui.service.UiText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatusBarViewModelTest {

    @Test
    void backgroundOperationTemporarilyOverridesAndThenRestoresForegroundState() {
        StatusBarViewModel vm = new StatusBarViewModel();
        vm.setStatusText("Колекцію активовано");
        vm.setProgress(0.25);
        vm.setProgressVisible(false);

        vm.setBackgroundOperation("Оновлення пошукового індексу…", -1.0, true);
        assertEquals("Оновлення пошукового індексу…", vm.statusTextProperty().get());
        assertEquals(-1.0, vm.progressProperty().get(), 0.0001);
        assertTrue(vm.progressVisibleProperty().get());

        // Foreground updates are remembered but must not hide an active blocking/background operation.
        vm.setStatusText("Інший UI-статус");
        vm.setProgress(0.75);
        assertEquals("Оновлення пошукового індексу…", vm.statusTextProperty().get());

        vm.clearBackgroundOperation();
        assertEquals("Інший UI-статус", vm.statusTextProperty().get());
        assertEquals(0.75, vm.progressProperty().get(), 0.0001);
        assertFalse(vm.progressVisibleProperty().get());
    }
    @Test
    void transientCompletionDoesNotResurfaceAfterLaterBackgroundOperation() {
        StatusBarViewModel vm = new StatusBarViewModel();
        vm.setTransientStatusText("Завантажено: Книга");
        assertEquals("Завантажено: Книга", vm.statusTextProperty().get());

        vm.setBackgroundOperation("Оновлення пошукового індексу…", -1.0, true);
        assertEquals("Оновлення пошукового індексу…", vm.statusTextProperty().get());

        vm.clearBackgroundOperation();
        assertEquals(UiText.text("ui.fxml.statusbar.ready"), vm.statusTextProperty().get());
        assertEquals(0.0, vm.progressProperty().get(), 0.0001);
        assertFalse(vm.progressVisibleProperty().get());
    }

    @Test
    void foregroundUpdateDuringBackgroundStillBecomesVisibleWhenBackgroundEnds() {
        StatusBarViewModel vm = new StatusBarViewModel();
        vm.setTransientStatusText("Завантажено: Стара книга");
        vm.setBackgroundOperation("Оновлення пошукового індексу…", -1.0, true);

        vm.setStatusText("Нове повідомлення користувача");
        vm.setProgress(0.5);
        vm.setProgressVisible(true);
        assertEquals("Оновлення пошукового індексу…", vm.statusTextProperty().get());

        vm.clearBackgroundOperation();
        assertEquals("Нове повідомлення користувача", vm.statusTextProperty().get());
        assertEquals(0.5, vm.progressProperty().get(), 0.0001);
        assertTrue(vm.progressVisibleProperty().get());
    }

}
