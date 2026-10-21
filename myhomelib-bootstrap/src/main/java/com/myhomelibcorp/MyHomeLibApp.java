package com.myhomelibcorp;

import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.startup.StartupOrchestrator;
import com.myhomelibcorp.startup.StartupReport;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.RuntimeEncodingGuard;
import com.myhomelibcorp.ui.service.ApplicationThemeService;
import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.controller.MainController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootApplication(scanBasePackages = {
        "com.myhomelibcorp.application",
        "com.myhomelibcorp.infrastructure",
        "com.myhomelibcorp.ui",
        "com.myhomelibcorp.integration",
        "com.myhomelibcorp.startup",
        "com.myhomelibcorp.opds",
        "com.myhomelibcorp.organizer",
        "com.myhomelibcorp.epub.optimizer",
        "com.myhomelibcorp.converter.toolchain"
})
@EnableAsync
@Slf4j
public class MyHomeLibApp extends Application {

    private ConfigurableApplicationContext context;
    private static ConfigurableApplicationContext applicationContext;
    private Stage splashStage;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private com.myhomelibcorp.diagnostics.FxStallWatchdog fxStallWatchdog;

    @Override
    public void init() {
        // Keep JavaFX init intentionally lightweight. Spring used to be started here, before
        // JavaFX was allowed to show any stage, which caused several seconds of a completely
        // blank desktop before the splash screen appeared. Backend boot now starts only after
        // the splash is visible. Resolve only the tiny language.txt file here so JavaFX's own
        // control bundles see the user's locale before the first splash Control is created.
        configureEarlyJvmLocale();
        log.info("JavaFX bootstrap initialized");
    }

    public static ConfigurableApplicationContext getContext() {
        return applicationContext;
    }

    // language.txt normally contains one tiny BCP-47 tag. Never preload arbitrary-sized
    // configuration into the heap before the JavaFX splash and Spring start.
    static final int MAX_EARLY_LANGUAGE_FILE_BYTES = 4096;

    static String readEarlyLanguageTag(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_EARLY_LANGUAGE_FILE_BYTES)
                return "uk";
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file)) {
                bytes = input.readNBytes(MAX_EARLY_LANGUAGE_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_EARLY_LANGUAGE_FILE_BYTES) return "uk";
            return new String(bytes, StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .findFirst().orElse("uk");
        } catch (Exception ignored) {
            // LocalizationService applies the authoritative language setting after startup.
            return "uk";
        }
    }

    private static void configureEarlyJvmLocale() {
        String code = readEarlyLanguageTag(AppPaths.configDir().resolve("language.txt"));
        Locale locale = Locale.forLanguageTag(code.replace('_', '-'));
        if (!locale.getLanguage().isBlank()) Locale.setDefault(locale);
    }

    @Override
    public void start(Stage primaryStage) {
        splashStage = buildSplashStage();
        splashStage.show();
        updateSplash("Ініціалізація компонентів…");

        Thread startupThread = new Thread(() -> {
            try {
                log.info("Запуск Spring Boot контексту...");
                context = SpringApplication.run(MyHomeLibApp.class);
                applicationContext = context;
                context.getBean(LocalizationService.class).language();
                log.info("Spring Boot контекст завантажено");

                updateSplash("Завантаження колекції…");
                StartupOrchestrator startupOrchestrator = context.getBean(StartupOrchestrator.class);
                StartupReport report = startupOrchestrator.run(taskId ->
                        updateSplash(startupStatus(taskId)));

                updateSplash("Підготовка інтерфейсу…");
                Platform.runLater(() -> {
                    try {
                        if (report.degraded()) {
                            log.warn("Startup completed in degraded mode: {}", report.outcomes());
                        }
                        showMainWindow(primaryStage, report);
                        // Close the splash only after the main window is fully constructed and shown.
                        // This removes the previous blank gap between splash and ready UI.
                        splashStage.close();
                    } catch (Exception e) {
                        log.error("Помилка показу головного вікна", e);
                        splashStage.close();
                        showErrorAndExit(e);
                    }
                });
            } catch (Throwable ex) {
                log.error("Помилка запуску програми", ex);
                Platform.runLater(() -> {
                    if (splashStage != null) splashStage.close();
                    showErrorAndExit(ex);
                });
            }
        }, "myhomelib-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    private Label splashStatusLabel;

    private void updateSplash(String status) {
        Platform.runLater(() -> {
            if (splashStatusLabel != null && status != null && !status.isBlank()) {
                splashStatusLabel.setText(status);
            }
        });
    }

    private static String startupStatus(String taskId) {
        if (taskId == null) return "Завантаження колекції…";
        return switch (taskId) {
            case "RecoveryStartupTask" -> "Перевірка цілісності даних…";
            case "MigrationStartupTask" -> "Підготовка бази даних…";
            case "SearchStartupTask" -> "Підготовка пошуку…";
            case "ContentIndexingStartupTask" -> "Перевірка індексації…";
            case "BackupStartupTask" -> "Перевірка резервних копій…";
            case "OPDSStartupTask" -> "Підготовка сервісів…";
            default -> "Завантаження колекції…";
        };
    }

    // ==================== Splash Screen ====================

    private Stage buildSplashStage() {
        Stage splash = new Stage();
        ProgressIndicator indicator = new ProgressIndicator();
        splashStatusLabel = new Label(UiText.text("ui.bootstrap.loading_library"));
        VBox box = new VBox(15, indicator, splashStatusLabel);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setPadding(new javafx.geometry.Insets(30));
        Scene scene = new Scene(box, 320, 180);
        splash.setScene(scene);
        splash.setTitle(UiText.text("common.app_name"));
        splash.setAlwaysOnTop(true);
        return splash;
    }

    private void showMainWindow(Stage primaryStage, StartupReport startupReport) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/MainView.fxml"));
        if (loader.getLocation() == null) {
            throw new RuntimeException("MainView.fxml не знайдено у ресурсах");
        }
        loader.setControllerFactory(context::getBean);
        SessionService sessionService = context.getBean(SessionService.class);

        // Startup may race with a background INDEX operation. Workspace restoration is a
        // best-effort optimization; a coordinator conflict must not prevent the main window
        // from opening. Read the workspace state defensively and continue without it on conflict.
        SessionService.WorkspaceState restoreState = null;
        try {
            restoreState = sessionService.getWorkspaceState();
        } catch (com.myhomelibcorp.application.operation.LibraryOperationConflictException conflict) {
            log.warn("Не вдалося відновити workspace під час старту ({}); продовжуємо без відновлення",
                    conflict.getMessage());
        }

        Parent root = loader.load();
        MainController mainController = loader.getController();
        if (restoreState != null) mainController.restoreSessionWorkspace(restoreState);

        Collection activeCollection = startupReport.activeCollection();
        String collectionName = activeCollection != null ? activeCollection.getName() : UiText.text("ui.bootstrap.no_collection");
        primaryStage.setTitle(UiText.format("ui.bootstrap.main_window_title", collectionName));
        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double minWidth = Math.min(800.0, visualBounds.getWidth());
        double minHeight = Math.min(600.0, visualBounds.getHeight());
        double initialWidth = Math.min(1100.0, visualBounds.getWidth());
        double initialHeight = Math.min(750.0, visualBounds.getHeight());
        primaryStage.setScene(new Scene(root, initialWidth, initialHeight));
        context.getBean(ApplicationThemeService.class).start();
        primaryStage.setMinWidth(minWidth);
        primaryStage.setMinHeight(minHeight);
        if (sessionService.isRestoreEnabled()) {
            double[] windowState = sessionService.getWindowState();
            primaryStage.setWidth(Math.min(visualBounds.getWidth(), Math.max(minWidth, windowState[0])));
            primaryStage.setHeight(Math.min(visualBounds.getHeight(), Math.max(minHeight, windowState[1])));
        }
        primaryStage.centerOnScreen();
        primaryStage.setOnHiding(event -> sessionService.saveWindowState(primaryStage.getWidth(), primaryStage.getHeight()));
        primaryStage.show();
        fxStallWatchdog = new com.myhomelibcorp.diagnostics.FxStallWatchdog();
        fxStallWatchdog.start();
        com.myhomelibcorp.update.ApplicationUpdateHealthMarker.markHealthyIfRequested();
    }

    private void showErrorAndExit(Throwable ex) {
        Alert alert = DialogLocalization.prepare(new Alert(Alert.AlertType.ERROR));
        alert.setTitle(UiText.text("ui.bootstrap.startup_error_title"));
        alert.setHeaderText(UiText.text("ui.bootstrap.startup_error_header"));
        alert.setContentText(UiText.format("ui.bootstrap.startup_error_content", UiExceptionSupport.message(ex)));
        alert.showAndWait();
        Platform.exit();
    }

    // ==================== Завершення роботи ====================

    @Override
    public void stop() {
        if (isShuttingDown.getAndSet(true)) {
            log.info("Завершення програми вже виконується, пропускаємо повторний виклик");
            return;
        }

        log.info("Завершення програми...");
        if (fxStallWatchdog != null) fxStallWatchdog.close();

        // 1. Dispose active workspace/Reader before stopping executors and DB.
        if (context == null) {
            // Startup may have failed or the user may have closed the splash before Spring finished.
            // There is no backend to dispose in that case, but JavaFX still needs a clean exit.
            applicationContext = null;
            Platform.exit();
            log.info("Програма завершена до ініціалізації Spring контексту");
            return;
        }
        try {
            context.getBean(com.myhomelibcorp.ui.navigation.WorkspaceManager.class).disposeCurrent();
            log.info("Активний workspace закрито");
        } catch (Exception e) {
            log.warn("Не вдалося закрити активний workspace", e);
        }

        // 2. Stop coordinated maintenance while its executor is still alive. Otherwise a long
        // Lucene rebuild can keep AsyncConfig busy until the executor shutdown timeout expires.
        try {
            context.getBean(com.myhomelibcorp.application.service.CollectionLifecycleService.class)
                    .cancelBackgroundRebuildAndAwait();
        } catch (Exception e) {
            log.warn("Не вдалося скасувати фонову перебудову індексу", e);
        }

        // 3. Закриваємо AsyncConfig
        try {
            var asyncConfig = context.getBean(com.myhomelibcorp.infrastructure.config.AsyncConfig.class);
            asyncConfig.shutdown();
        } catch (Exception e) {
            log.warn("Не вдалося закрити AsyncConfig", e);
        }

        // 4. Закриваємо UiBackgroundExecutor
        try {
            var uiExecutor = context.getBean(com.myhomelibcorp.ui.service.UiBackgroundExecutor.class);
            uiExecutor.shutdown();
        } catch (Exception e) {
            log.warn("Не вдалося закрити UiBackgroundExecutor", e);
        }

        // 5. Shared task/io/import/search executors are owned and stopped by AsyncConfig.

        // 6. Закриваємо активну колекцію через єдиний lifecycle:
        // Lucene commit/close -> SQLite close/checkpoint -> freshness seal.
        if (context != null) {
            try {
                context.getBean(com.myhomelibcorp.application.service.CollectionLifecycleService.class).closeCollection();
            } catch (Exception e) {
                log.warn("Помилка коректного закриття колекції", e);
            }
            context.close(); // @PreDestroy закриє вже неактивний Lucene service та інші beans.
            applicationContext = null;
            context = null;
        }

        Platform.exit();
        log.info("Програма завершена");
    }

    public static void main(String[] args) {
        RuntimeEncodingGuard.warnIfNeeded();
        AppPaths.configureSystemProperties();
        if (java.util.Arrays.asList(args).contains("--release-smoke")) {
            try {
                ReleaseSmokeCheck.run();
                return;
            } catch (Exception e) {
                System.err.println("MYHOMELIB_RELEASE_SMOKE_FAILED: " + e.getMessage());
                e.printStackTrace(System.err);
                System.exit(2);
            }
        }
        launch(args);
    }
}