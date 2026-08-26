package com.myhomelibcorp;

import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.application.usecase.series.SyncSeriesUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.importer.inpx.InpxImporter;
import com.myhomelibcorp.infrastructure.initializer.DatabaseInitializer;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteCollectionRepository;
import com.myhomelibcorp.infrastructure.search.LuceneSearchService;
import com.myhomelibcorp.infrastructure.warmup.BackgroundWarmup;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

        CompletableFuture.supplyAsync(() -> {
            try {
                return initializeBackend(primaryStage);
            } catch (Exception e) {
                log.error("Помилка ініціалізації бекенду", e);
                throw new RuntimeException(e);
            }
        }).thenAccept(collectionManager -> {
            Platform.runLater(() -> {
                splashStage.close();
                try {
                    showMainWindow(primaryStage, collectionManager);
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

    private CollectionManager initializeBackend(Stage primaryStage) {
        CollectionManager collectionManager = context.getBean(CollectionManager.class);
        SqliteCollectionRepository collectionRepository = context.getBean(SqliteCollectionRepository.class);
        DatabaseInitializer initializer = context.getBean(DatabaseInitializer.class);
        SwitchCollectionUseCase switchCollectionUseCase = context.getBean(SwitchCollectionUseCase.class);
        SyncSeriesUseCase syncSeriesUseCase = context.getBean(SyncSeriesUseCase.class);

        // ОТРИМУЄМО ВСІ КОЛЕКЦІЇ З МЕТА-БД
        List<Collection> collections = collectionRepository.findAll();
        log.info("Знайдено {} колекцій при старті", collections.size());

        Collection active;
        if (collections.isEmpty()) {
            log.info("Колекцій не знайдено, створюємо стандартну...");
            String dbPath = AppPaths.librariesDir().resolve(UUID.randomUUID() + ".db").toString();
            active = collectionRepository.save(new Collection(
                    null,
                    "Моя бібліотека",
                    null,
                    dbPath,
                    0,
                    null,
                    null,
                    null,
                    null
            ));
            log.info("Створено стандартну колекцію: id={}, dbFile={}", active.getId(), active.getDbFile());
        } else {
            // БЕРЕМО ПЕРШУ КОЛЕКЦІЮ ЯК АКТИВНУ (можна змінити на останню використану)
            active = collections.get(0);
            log.info("Використовуємо першу колекцію: id={}, name={}, dbFile={}",
                    active.getId(), active.getName(), active.getDbFile());

            // Логуємо всі знайдені колекції
            for (Collection c : collections) {
                log.info("  - Колекція: {} (id={}, dbFile={})", c.getName(), c.getId(), c.getDbFile());
            }
        }

        // 1. Переключаємо колекцію
        switchCollectionUseCase.execute(active);
        context.getBean(ApplicationState.class).setCurrentLibraryCollection(active);

        // 2. Синхронізуємо серії
        syncSeriesUseCase.execute();

        // 3. Ініціалізуємо базу даних
        initializer.initializeCurrentCollection();

        // 4. Оновлюємо статистику
        try {
            StatisticsService statisticsService = context.getBean(StatisticsService.class);
            statisticsService.refreshStatistics();
        } catch (Exception e) {
            log.warn("Не вдалося оновити статистику (можливо, ще не виконана міграція)", e);
        }

        // 5. Завантажуємо кеші
        DictionaryCachePort dictCache = context.getBean(DictionaryCachePort.class);
        GenreRepository genreRepo = context.getBean(GenreRepository.class);
        SeriesRepository seriesRepo = context.getBean(SeriesRepository.class);
        GroupRepository groupRepo = context.getBean(GroupRepository.class);

        dictCache.loadGenres(genreRepo.findAll());
        dictCache.loadSeries(seriesRepo.findAll());
        dictCache.loadGroups(groupRepo.findAll());

        InpxImporter inpxImporter = context.getBean(InpxImporter.class);
        inpxImporter.initialize();

        // 6. Перебудовуємо Lucene індекс
        try {
            log.info("Перебудова Lucene індексу...");
            var luceneService = context.getBean(LuceneSearchService.class);
            luceneService.rebuildIndex();
            log.info("Lucene індекс перебудовано. Проіндексовано {} книг", luceneService.getDocumentCount());
        } catch (Exception e) {
            log.error("Помилка перебудови Lucene індексу", e);
        }

        BackgroundWarmup backgroundWarmup = context.getBean(BackgroundWarmup.class);
        backgroundWarmup.warmup();

        log.info("Всі кеші та компоненти готові до роботи");
        return collectionManager;
    }

    private void showMainWindow(Stage primaryStage, CollectionManager collectionManager) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/MainView.fxml"));
        if (loader.getLocation() == null) {
            throw new RuntimeException("MainView.fxml не знайдено у ресурсах");
        }
        loader.setControllerFactory(context::getBean);
        Parent root = loader.load();

        String collectionName = collectionManager.getCurrentCollection() != null
                ? collectionManager.getCurrentCollection().getName()
                : "Без колекції";
        primaryStage.setTitle("MyHomeLib — " + collectionName);
        primaryStage.setScene(new Scene(root, 1100, 750));
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);
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

        // 4. Закриваємо BackgroundExecutor
        try {
            var bgExecutor = context.getBean(com.myhomelibcorp.infrastructure.executor.BackgroundExecutor.class);
            bgExecutor.shutdown();
        } catch (Exception e) {
            log.warn("Не вдалося закрити BackgroundExecutor", e);
        }

        // 5. Закриваємо BackgroundTaskService
        try {
            var bgTaskService = context.getBean(com.myhomelibcorp.ui.service.BackgroundTaskService.class);
            bgTaskService.shutdown();
        } catch (Exception e) {
            log.warn("Не вдалося закрити BackgroundTaskService", e);
        }

        // 6. Закриваємо SpringExecutorAdapter
        try {
            var executorAdapter = context.getBean(com.myhomelibcorp.infrastructure.executor.SpringExecutorAdapter.class);
            executorAdapter.shutdown();
        } catch (Exception e) {
            log.warn("Не вдалося закрити SpringExecutorAdapter", e);
        }

        // 7. Закриваємо LuceneSearchService
        try {
            var luceneService = context.getBean(LuceneSearchService.class);
            luceneService.close();
            log.info("LuceneSearchService закрито");
        } catch (Exception e) {
            log.warn("Не вдалося закрити LuceneSearchService", e);
        }

        // 8. Закриваємо CollectionManager
        if (context != null) {
            try {
                CollectionManager collectionManager = context.getBean(CollectionManager.class);
                collectionManager.closeCurrentCollection();
                log.info("CollectionManager закрито");
            } catch (Exception e) {
                log.warn("Помилка закриття колекції", e);
            }
            context.close();
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