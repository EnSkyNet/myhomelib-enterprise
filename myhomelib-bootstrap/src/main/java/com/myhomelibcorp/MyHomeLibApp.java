package com.myhomelibcorp;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.initializer.DatabaseInitializer;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteCollectionRepository;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
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

            // ------ Робота з колекцією ------
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
                log.info("Створено та вибрано колекцію: {}", saved.getName());
                initializer.initializeCurrentCollection();
            } else {
                Collection first = collections.get(0);
                collectionManager.switchToCollection(first);
                log.info("Вибрано колекцію: {}", first.getName());
                initializer.initializeCurrentCollection();
            }

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

            // ------ Відображення вікна ------
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
            log.error("КРИТИЧНА ПОМИЛКА ПРИ ЗАПУСКУ JavaFX", e);
            // Показуємо діалог з помилкою
            Platform.runLater(() -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR);
                alert.setTitle("Помилка запуску");
                alert.setHeaderText("Не вдалося запустити програму");
                alert.setContentText("Деталі: " + e.getMessage() + "\n\nДивіться логи для повної інформації.");
                alert.showAndWait();
            });
            // Завершуємо програму після показу помилки
            Platform.exit();
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