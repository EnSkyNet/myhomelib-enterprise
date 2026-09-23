package com.myhomelibcorp.ui.service;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogLocalizationFxTest {
    @BeforeAll
    static void startFx() throws Exception {
        if (Platform.isFxApplicationThread()) return;
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void localizesButtonsAlreadyPresentOnAlertWithoutChangingButtonTypeIdentity() throws Exception {
        runFx(() -> {
            Alert alert = DialogLocalization.prepare(new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "test",
                    ButtonType.YES,
                    ButtonType.NO,
                    ButtonType.CANCEL));

            assertButton(alert, ButtonType.YES, UiText.text("common.yes"));
            assertButton(alert, ButtonType.NO, UiText.text("common.no"));
            assertButton(alert, ButtonType.CANCEL, UiText.text("common.cancel"));
            assertSame(ButtonType.YES, alert.getDialogPane().getButtonTypes().get(0));
            assertSame(ButtonType.NO, alert.getDialogPane().getButtonTypes().get(1));
            assertSame(ButtonType.CANCEL, alert.getDialogPane().getButtonTypes().get(2));
        });
    }

    @Test
    void localizesStandardButtonsAddedAfterPrepare() throws Exception {
        runFx(() -> {
            Dialog<ButtonType> dialog = DialogLocalization.prepare(new Dialog<>());
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CLOSE);

            assertButton(dialog, ButtonType.OK, UiText.text("common.ok"));
            assertButton(dialog, ButtonType.CLOSE, UiText.text("common.close"));
        });
    }

    private static void assertButton(Dialog<?> dialog, ButtonType type, String expected) {
        Node node = dialog.getDialogPane().lookupButton(type);
        assertTrue(node instanceof Labeled, () -> "No rendered Labeled for " + type);
        assertEquals(expected, ((Labeled) node).getText());
    }

    private static void runFx(Runnable action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            if (failure.get() instanceof Exception e) throw e;
            if (failure.get() instanceof Error e) throw e;
            throw new AssertionError(failure.get());
        }
    }
}
