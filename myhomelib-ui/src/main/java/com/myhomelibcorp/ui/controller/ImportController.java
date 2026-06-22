package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.importer.api.ImporterApplicationService;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportController {

    private final ImporterApplicationService importerService;
    private final BackgroundExecutor backgroundExecutor;

    private ProgressBar progressBar;
    private Label statusLabel;
    private Runnable onImportComplete;

    public void setupImport(ProgressBar progressBar, Label statusLabel, Runnable onImportComplete) {
        this.progressBar = progressBar;
        this.statusLabel = statusLabel;
        this.onImportComplete = onImportComplete;
    }

    public void importFb2() {
        log.info("Імпорт FB2");
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть FB2 файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("FB2 файли", "*.fb2", "*.fbd")
        );
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            importFile(file.toPath());
        }
    }

    public void importInpx() {
        log.info("Імпорт INPX");
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть INPX файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("INPX файли", "*.inpx", "*.inp")
        );
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            importFile(file.toPath());
        }
    }

    public void importDirectory() {
        log.info("Імпорт каталогу");
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Виберіть каталог з книгами");
        File dir = directoryChooser.showDialog(null);
        if (dir != null && dir.isDirectory()) {
            importDirectory(dir.toPath());
        }
    }

    private void importFile(Path filePath) {
        progressBar.setVisible(true);
        statusLabel.setText("Імпорт: " + filePath.getFileName());

        backgroundExecutor.submit(() -> {
            int count = importerService.importBooks(filePath);
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("Імпорт завершено. Додано " + count + " книг");
                if (onImportComplete != null) {
                    onImportComplete.run();
                }
            });
            return count;
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("Помилка імпорту: " + ex.getMessage());
                log.error("Помилка імпорту", ex);
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Помилка імпорту");
                alert.setContentText(ex.getMessage());
                alert.showAndWait();
            });
            return null;
        });
    }

    private void importDirectory(Path dirPath) {
        progressBar.setVisible(true);
        statusLabel.setText("Імпорт каталогу: " + dirPath.getFileName());

        backgroundExecutor.submit(() -> {
            AtomicBoolean cancelFlag = new AtomicBoolean(false);
            DoubleConsumer progressConsumer = progress ->
                    Platform.runLater(() -> progressBar.setProgress(progress));
            int count = importerService.importDirectory(dirPath, progressConsumer, cancelFlag);
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("Імпорт каталогу завершено. Додано " + count + " книг");
                if (onImportComplete != null) {
                    onImportComplete.run();
                }
            });
            return count;
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("Помилка імпорту каталогу: " + ex.getMessage());
                log.error("Помилка імпорту каталогу", ex);
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Помилка імпорту");
                alert.setContentText(ex.getMessage());
                alert.showAndWait();
            });
            return null;
        });
    }
}
