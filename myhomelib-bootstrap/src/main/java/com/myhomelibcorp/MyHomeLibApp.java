package com.myhomelibcorp;

import com.myhomelibcorp.application.imports.duplicate.DuplicateDetector;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.cache.DictionaryCache;
import com.myhomelibcorp.infrastructure.cache.GlobalCache;
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
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.List;

@SpringBootApplication(scanBasePackages = "com.myhomelibcorp")
@EnableAsync
@Slf4j
public class MyHomeLibApp extends Application {

    private ConfigurableApplicationContext context;

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
        try {
            log.info("Запуск JavaFX...");
            System.setProperty("file.encoding", "UTF-8");

            CollectionManager collectionManager = context.getBean(CollectionManager.class);
            SqliteCollectionRepository collectionRepository = context.getBean(SqliteCollectionRepository.class);
            DatabaseInitializer initializer = context.getBean(DatabaseInitializer.class);

            // ------ Робота з колекціями ------
            List<Collection> collections = collectionRepository.findAll();
            if (collections.isEmpty()) {
                log.info("Колекцій не знайдено, створюємо стандартну...");
                Collection defaultCollection = new Collection(
                        null,
                        "Моя бібліотека",
                        null,
                        System.getProperty("user.home") + "/.myhomelibcorp/library.db",
                        0,
                        null,
                        null,
                        null,
                        null
                );
                Collection saved = collectionRepository.save(defaultCollection);
                collectionManager.switchToCollection(saved);
                log.info("Створено та активовано колекцію: {}", saved.getName());
                initializer.initializeCurrentCollection();
            } else {
                Collection first = collections.get(0);
                collectionManager.switchToCollection(first);
                log.info("Вибрано колекцію: {}", first.getName());
                initializer.initializeCurrentCollection();
            }

            // FIX: Оновлюємо статистику після ініціалізації БД
            try {
                StatisticsService statisticsService = context.getBean(StatisticsService.class);
                statisticsService.refreshStatistics();
                log.info("Статистику оновлено");
            } catch (Exception e) {
                log.warn("Не вдалося оновити статистику (можливо, відсутні колонки). Виконується міграція...");
                // Якщо міграція ще не виконана, Flyway додасть колонки при наступному запуску
            }

            // ------ Ініціалізація кешів та інших компонентів ------
            GlobalCache globalCache = context.getBean(GlobalCache.class);
            globalCache.initialize();

            DictionaryCache dictCache = context.getBean(DictionaryCache.class);
            AuthorRepository authorRepo = context.getBean(AuthorRepository.class);
            GenreRepository genreRepo = context.getBean(GenreRepository.class);
            SeriesRepository seriesRepo = context.getBean(SeriesRepository.class);
            GroupRepository groupRepo = context.getBean(GroupRepository.class);
            dictCache.loadAuthors(authorRepo.findAll());
            dictCache.loadGenres(genreRepo.findAll());
            dictCache.loadSeries(seriesRepo.findAll());
            dictCache.loadGroups(groupRepo.findAll());

            DuplicateDetector duplicateDetector = context.getBean(DuplicateDetector.class);
            duplicateDetector.loadExistingKeys();

            InpxImporter inpxImporter = context.getBean(InpxImporter.class);
            inpxImporter.initialize();

            // ------ Запуск BackgroundWarmup ------
            BackgroundWarmup backgroundWarmup = context.getBean(BackgroundWarmup.class);
            backgroundWarmup.warmup();

            log.info("Всі кеші та компоненти ініціалізовано");

            // ------ Завантаження FXML ------
            log.info("Спроба завантажити /view/MainView.fxml");
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/MainView.fxml"));
            if (loader.getLocation() == null) {
                log.error("Файл MainView.fxml не знайдено!");
                throw new RuntimeException("MainView.fxml відсутній у ресурсах");
            }
            log.info("FXML знайдено, встановлюємо controller factory...");
            loader.setControllerFactory(context::getBean);
            log.info("Завантаження FXML...");
            Parent root = loader.load();
            log.info("FXML завантажено успішно");

            // ------ Налаштування вікна ------
            String collectionName = collectionManager.getCurrentCollection() != null
                    ? collectionManager.getCurrentCollection().getName()
                    : "Без колекції";
            primaryStage.setTitle("MyHomeLib Enterprise – " + collectionName);
            primaryStage.setScene(new Scene(root, 1100, 750));
            primaryStage.setMinWidth(800);
            primaryStage.setMinHeight(600);
            primaryStage.show();

            log.info("JavaFX вікно відкрито");

        } catch (Exception e) {
            log.error("Помилка під час запуску JavaFX", e);
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Помилка запуску");
                alert.setHeaderText("Не вдалося запустити програму");
                alert.setContentText("Деталі: " + e.getMessage() + "\n\nПеревірте логи.");
                alert.showAndWait();
                Platform.exit();
            });
        }
    }

    @Override
    public void stop() {
        log.info("Завершення програми...");
        if (context != null) {
            try {
                CollectionManager collectionManager = context.getBean(CollectionManager.class);
                collectionManager.closeCurrentCollection();
            } catch (Exception e) {
                log.warn("Помилка закриття колекції", e);
            }
            context.close();
        }
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}