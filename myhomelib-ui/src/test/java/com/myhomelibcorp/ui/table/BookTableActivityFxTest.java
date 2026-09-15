package com.myhomelibcorp.ui.table;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TableColumn;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Display-capable FXML contract for the catalogue activity column added in Iteration 85. */
class BookTableActivityFxTest {
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
    void bookTableFxmlExposesNonSortableActivityColumn() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<TableColumn<?, ?>> activity = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/book-table.fxml"));
                loader.setControllerFactory(type -> mock(type));
                Parent root = loader.load();
                assertThat(root).isNotNull();
                activity.set((TableColumn<?, ?>) loader.getNamespace().get("activityColumn"));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertThat(activity.get()).isNotNull();
        assertThat(activity.get().isSortable()).isFalse();
        assertThat(activity.get().getPrefWidth()).isGreaterThanOrEqualTo(120.0);
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
