package com.myhomelibcorp;

import com.myhomelibcorp.application.imports.duplicate.DuplicateDetector;
import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.cache.DictionaryCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.importer.inpx.InpxImporter;
import com.myhomelibcorp.infrastructure.initializer.DatabaseInitializer;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteCollectionRepository;
import com.myhomelibcorp.infrastructure.warmup.BackgroundWarmup;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SpringBootApplication(scanBasePackages = "com.myhomelibcorp")
@EnableAsync
@Slf4j
public class MyHomeLibApp extends Application {

    private ConfigurableApplicationContext context;
    private Stage splashStage;

    @Override
    public void init() {
        try {
            log.info("Запуск Spring Boot контексту...");
            context = SpringApplication.run(MyHomeLibApp.class);
            log.info("Spring Boot контекст запущено");
        } catch (Exception e) {
            log.error("Помилка ініціалізації Spring Boot", e);
            throw new RuntimeException("Не вдалося запустити Spring Boot", e);
        }
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

    private CollectionManager initializeBackend(Stage primaryStage) {
        CollectionManager collectionManager = context.getBean(CollectionManager.class);
        SqliteCollectionRepository collectionRepository = context.getBean(SqliteCollectionRepository.class);
        DatabaseInitializer initializer = context.getBean(DatabaseInitializer.class);

        List<Collection> collections = collectionRepository.findAll();
        Collection active;
        if (collections.isEmpty()) {
            log.info("Колекцій не знайдено, створюємо стандартну...");
            active = collectionRepository.save(new Collection(
                    null,
                    "Моя бібліотека",
                    null,
                    System.getProperty("user.home") + "/.myhomelibcorp/library.db",
                    0,
                    null,
                    null,
                    null,
                    null
            ));
        } else {
            active = collections.get(0);
        }

        collectionManager.switchToCollection(active);
        initializer.initializeCurrentCollection();

        try {
            StatisticsService statisticsService = context.getBean(StatisticsService.class);
            statisticsService.refreshStatistics();
        } catch (Exception e) {
            log.warn("Не вдалося оновити статистику (можливо, ще не виконана міграція)", e);
        }

        DictionaryCachePort dictCache = context.getBean(DictionaryCachePort.class);

        AuthorRepository authorRepo = context.getBean(AuthorRepository.class);
        GenreRepository genreRepo = context.getBean(GenreRepository.class);
        SeriesRepository seriesRepo = context.getBean(SeriesRepository.class);
        GroupRepository groupRepo = context.getBean(GroupRepository.class);

        dictCache.loadAuthors(authorRepo.findAll());
        dictCache.loadGenres(genreRepo.findAll());
        dictCache.loadSeries(seriesRepo.findAll());
        dictCache.loadGroups(groupRepo.findAll());

        DuplicateDetector duplicateDetector = context.getBean(DuplicateDetector.class);

        InpxImporter inpxImporter = context.getBean(InpxImporter.class);
        inpxImporter.initialize();

        BackgroundWarmup backgroundWarmup = context.getBean(BackgroundWarmup.class);
        backgroundWarmup.warmup();

        log.info("Всі кеші та компоненти ініціалізовано");
        return collectionManager;
    }

    private void showMainWindow(Stage primaryStage, CollectionManager collectionManager) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/MainView.fxml"));
        if (loader.getLocation() == null) {
            throw new RuntimeException("MainView.fxml відсутній у ресурсах");
        }
        loader.setControllerFactory(context::getBean);
        Parent root = loader.load();

        String collectionName = collectionManager.getCurrentCollection() != null
                ? collectionManager.getCurrentCollection().getName()
                : "Без колекції";
        primaryStage.setTitle("MyHomeLib Enterprise – " + collectionName);
        primaryStage.setScene(new Scene(root, 1100, 750));
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);
        primaryStage.show();
    }

    private Stage buildSplashStage() {
        Stage splash = new Stage();
        ProgressIndicator indicator = new ProgressIndicator();
        Label label = new Label("Завантаження бібліотеки...");
        VBox box = new VBox(15, indicator, label);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setPadding(new javafx.geometry.Insets(30));
        Scene scene = new Scene(box, 320, 180);
        splash.setScene(scene);
        splash.setTitle("MyHomeLib Enterprise");
        splash.setAlwaysOnTop(true);
        return splash;
    }

    private void showErrorAndExit(Throwable ex) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Помилка запуску");
        alert.setHeaderText("Не вдалося запустити програму");
        alert.setContentText("Деталі: " + ex.getMessage() + "\n\nПеревірте логи.");
        alert.showAndWait();
        Platform.exit();
    }

    @Override
    public void stop() {
        log.info("Завершення програми...");

        // 1. Закриваємо всі пули потоків з AsyncConfig
        try {
            var asyncConfig = context.getBean(com.myhomelibcorp.infrastructure.config.AsyncConfig.class);
            // Закриття пулів через @PreDestroy викликається автоматично, але якщо ні — робимо вручну
        } catch (Exception e) {
            log.warn("Не вдалося отримати AsyncConfig", e);
        }

        // 2. Закриваємо UiBackgroundExecutor
        try {
            var uiExecutor = context.getBean(com.myhomelibcorp.ui.service.UiBackgroundExecutor.class);
            uiExecutor.shutdown();
        } catch (Exception e) {
            log.warn("Не вдалося закрити UiBackgroundExecutor", e);
        }

        // 3. Закриваємо BackgroundExecutor
        try {
            var bgExecutor = context.getBean(com.myhomelibcorp.infrastructure.executor.BackgroundExecutor.class);
            bgExecutor.shutdown();
        } catch (Exception e) {
            log.warn("Не вдалося закрити BackgroundExecutor", e);
        }

        // 4. Закриваємо Lucene IndexWriter
        try {
            var luceneEngine = context.getBean(com.myhomelibcorp.infrastructure.search.LuceneSearchEngine.class);
            luceneEngine.close();
        } catch (Exception e) {
            log.warn("Не вдалося закрити LuceneSearchEngine", e);
        }

        // 5. Закриваємо поточну колекцію
        if (context != null) {
            try {
                CollectionManager collectionManager = context.getBean(CollectionManager.class);
                collectionManager.closeCurrentCollection();
            } catch (Exception e) {
                log.warn("Помилка закриття колекції", e);
            }
            // 6. Закриваємо Spring контекст
            context.close();
        }

        // 7. Завершуємо JavaFX
        Platform.exit();
        log.info("Програму завершено");
    }

    public static void main(String[] args) {
        launch(args);
    }
}