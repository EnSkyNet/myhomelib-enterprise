package com.myhomelibcorp;

import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.startup.StartupOrchestrator;
import com.myhomelibcorp.startup.StartupReport;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.ui.service.ApplicationThemeService;
import com.myhomelibcorp.ui.controller.MainController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootApplication(scanBasePackages = "com.myhomelibcorp")
@EnableAsync
@Slf4j
public class MyHomeLibApp extends Application {

    private ConfigurableApplicationContext context;
    private static ConfigurableApplicationContext applicationContext;
    private Stage splashStage;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    @Override
    public void init() {
        try {
            log.info("Запуск Spring Boot контексту...");
            context = SpringApplication.run(MyHomeLibApp.class);
            applicationContext = context;
            log.info("Spring Boot контекст завантажено");
        } catch (Exception e) {
            log.error("Помилка ініціалізації Spring Boot", e);
            throw new RuntimeException("Не вдалося завантажити Spring Boot", e);
        }
    }

    public static ConfigurableApplicationContext getContext() {
        return applicationContext;
    }

    @Override
    public void start(Stage primaryStage) {
        System.setProperty("file.encoding", "UTF-8");

        splashStage = buildSplashStage();
        splashStage.show();

        var startupExecutor = context.getBean(com.myhomelibcorp.application.port.out.executor.ExecutorPort.class);
        StartupOrchestrator startupOrchestrator = context.getBean(StartupOrchestrator.class);
        startupExecutor.submit(startupOrchestrator::run).thenAccept(report -> {
            Platform.runLater(() -> {
                splashStage.close();
                try {
                    if (report.degraded()) {
                        log.warn("Startup completed in degraded mode: {}", report.outcomes());
                    }
                    showMainWindow(primaryStage, report);
                } catch (Exception e) {
                    log.error("Помилка показу головного вікна", e);
                    showErrorAndExit(e);
                }
            });
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                splashStage.close();
                showErrorAndExit(ex);
            });
            return null;
        });
    }

    // ==================== Splash Screen ====================

    private Stage buildSplashStage() {
        Stage splash = new Stage();
        ProgressIndicator indicator = new ProgressIndicator();
        Label label = new Label("Завантаження бібліотеки...");
        VBox box = new VBox(15, indicator, label);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setPadding(new javafx.geometry.Insets(30));
        Scene scene = new Scene(box, 320, 180);
        splash.setScene(scene);
        splash.setTitle("MyHomeLib");
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
        SessionService.WorkspaceState restoreState = sessionService.getWorkspaceState();
        Parent root = loader.load();
        MainController mainController = loader.getController();
        if (restoreState != null) mainController.restoreSessionWorkspace(restoreState);

        Collection activeCollection = startupReport.activeCollection();
        String collectionName = activeCollection != null ? activeCollection.getName() : "Без колекції";
        primaryStage.setTitle("MyHomeLib — " + collectionName);
        primaryStage.setScene(new Scene(root, 1100, 750));
        context.getBean(ApplicationThemeService.class).start();
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);
        if (sessionService.isRestoreEnabled()) {
            double[] windowState = sessionService.getWindowState();
            primaryStage.setWidth(Math.max(800, windowState[0]));
            primaryStage.setHeight(Math.max(600, windowState[1]));
        }
        primaryStage.setOnHiding(event -> sessionService.saveWindowState(primaryStage.getWidth(), primaryStage.getHeight()));
        primaryStage.show();
    }

    private void showErrorAndExit(Throwable ex) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Помилка запуску");
        alert.setHeaderText("Не вдалося запустити програму");
        alert.setContentText("Деталі: " + ex.getMessage() + "\n\nПеревірте логи.");
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

        // 1. Dispose active workspace/Reader before stopping executors and DB.
        try {
            context.getBean(com.myhomelibcorp.ui.navigation.WorkspaceManager.class).disposeCurrent();
            log.info("Активний workspace закрито");
        } catch (Exception e) {
            log.warn("Не вдалося закрити активний workspace", e);
        }

        // 2. Закриваємо AsyncConfig
        try {
            var asyncConfig = context.getBean(com.myhomelibcorp.infrastructure.config.AsyncConfig.class);
            asyncConfig.shutdown();
        } catch (Exception e) {
            log.warn("Не вдалося закрити AsyncConfig", e);
        }

        // 3. Закриваємо UiBackgroundExecutor
        try {
            var uiExecutor = context.getBean(com.myhomelibcorp.ui.service.UiBackgroundExecutor.class);
            uiExecutor.shutdown();
        } catch (Exception e) {
            log.warn("Не вдалося закрити UiBackgroundExecutor", e);
        }

        // 4. Shared task/io/import/search executors are owned and stopped by AsyncConfig.

        // 5. Закриваємо активну колекцію через єдиний lifecycle:
        // Lucene commit/close -> SQLite close/checkpoint -> freshness seal.
        if (context != null) {
            try {
                context.getBean(com.myhomelibcorp.application.service.CollectionLifecycleService.class).closeCollection();
            } catch (Exception e) {
                log.warn("Помилка коректного закриття колекції", e);
            }
            context.close(); // @PreDestroy закриє вже неактивний Lucene service та інші beans.
        }

        Platform.exit();
        log.info("Програма завершена");
    }

    public static void main(String[] args) {
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