package com.myhomelibcorp;

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

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication(scanBasePackages = "com.myhomelibcorp")
@EnableAsync
@Slf4j
public class MyHomeLibApp extends Application {

    private ConfigurableApplicationContext context;

    @Override
    public void init() {
        Path homeDir = Paths.get(System.getProperty("user.home"), ".myhomelibcorp");
        File dir = homeDir.toFile();
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                log.info("Створено домашню папку: {}", homeDir);
            } else {
                log.error("Не вдалося створити домашню папку: {}", homeDir);
            }
        }

        log.info("Запуск Spring Boot контексту...");
        context = SpringApplication.run(MyHomeLibApp.class);
        log.info("Spring Boot контекст запущено");
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        log.info("Запуск JavaFX...");
        System.setProperty("file.encoding", "UTF-8");

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/MainView.fxml"));
        loader.setControllerFactory(context::getBean);
        Parent root = loader.load();

        primaryStage.setTitle("MyHomeLib Enterprise");
        primaryStage.setScene(new Scene(root, 1100, 750));
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);
        primaryStage.show();

        log.info("JavaFX вікно відкрито");
    }

    @Override
    public void stop() {
        log.info("Завершення програми...");
        if (context != null) {
            context.close();
        }
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}