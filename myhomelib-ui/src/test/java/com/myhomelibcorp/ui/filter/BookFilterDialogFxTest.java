package com.myhomelibcorp.ui.filter;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Display-capable contract for the catalogue annotations/highlights filter added in Iteration 85. */
class BookFilterDialogFxTest {
    @BeforeAll
    static void startFx() throws Exception {
        assumeDisplayReachable();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        } catch (UnsupportedOperationException noDisplay) {
            Assumptions.abort("JavaFX runtime is not reachable: " + noDisplay.getMessage());
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Platform.setImplicitExit(false);
    }

    @Test
    void filterDialogExposesAccessibleAnnotationPresenceSelector() throws Exception {
        BookFilterStateService state = mock(BookFilterStateService.class);
        LocalizationService i18n = mock(LocalizationService.class);
        when(i18n.tr(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(i18n.text(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "ui.filter.annotation.label" -> "Анотації";
            case "ui.filter.annotation.any" -> "Будь-які";
            case "ui.filter.annotation.notes" -> "Тільки з нотатками";
            case "ui.filter.annotation.highlights" -> "Тільки з підсвітками";
            case "ui.filter.annotation.any_annotation" -> "З нотатками або підсвітками";
            default -> invocation.getArgument(0);
        });

        BookFilterDialogService service = new BookFilterDialogService(state, i18n);
        AtomicReference<Dialog<BookFilterSpec>> dialogRef = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                dialogRef.set(service.createDialog(null, BookFilterSpec.empty()));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());

        Dialog<BookFilterSpec> dialog = dialogRef.get();
        assertThat(dialog).isNotNull();
        Node node = dialog.getDialogPane().lookup("#annotationPresenceFilter");
        assertThat(node).isInstanceOf(ComboBox.class);
        ComboBox<?> combo = (ComboBox<?>) node;
        assertThat(combo.getAccessibleText()).isEqualTo("Анотації");
        assertThat(combo.getItems()).hasSize(4);
    }

    private static void assumeDisplayReachable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        String display = System.getenv("DISPLAY");
        Assumptions.assumeTrue(display != null && !display.isBlank(),
                "JavaFX runtime test requires DISPLAY on Linux");
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        } catch (Throwable unreachableDisplay) {
            Assumptions.abort("JavaFX DISPLAY is not reachable: " + unreachableDisplay.getMessage());
        }
    }
}
