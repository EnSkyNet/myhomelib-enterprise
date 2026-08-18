package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.dto.InpxExportRequest;
import com.myhomelibcorp.application.usecase.export.ExportToDeviceUseCase;
import com.myhomelibcorp.application.usecase.export.ExportToInpxUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExportController {

    private final ExportToDeviceUseCase exportToDeviceUseCase;
    private final ExportToInpxUseCase exportToInpxUseCase;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final ApplicationContext springContext;

    @FXML private ComboBox<ExportRequest.ExportFormat> formatComboBox;
    @FXML private TextField destinationField;
    @FXML private TextField fileNameTemplateField;
    @FXML private CheckBox overwriteCheckBox;
    @FXML private CheckBox extractOnlyCheckBox;
    @FXML private Label selectedBooksLabel;
    @FXML private Button exportButton;
    @FXML private Button cancelButton;

    private List<BookId> selectedBookIds;
    private Stage stage;

    @FXML
    public void initialize() {
        formatComboBox.getItems().setAll(ExportRequest.ExportFormat.values());
        formatComboBox.setValue(ExportRequest.ExportFormat.FB2);
        selectedBooksLabel.setText("0 книг вибрано");
        exportButton.setDisable(true);
    }

    public void setSelectedBooks(List<BookViewModel> selectedBooks) {
        if (selectedBooks == null || selectedBooks.isEmpty()) {
            this.selectedBookIds = List.of();
            selectedBooksLabel.setText("0 книг вибрано");
            exportButton.setDisable(true);
            return;
        }

        this.selectedBookIds = selectedBooks.stream()
                .map(b -> BookId.fromString(b.getId()))
                .collect(Collectors.toList());
        selectedBooksLabel.setText(selectedBooks.size() + " книг вибрано");
        exportButton.setDisable(false);
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    // ==================== ЕКСПОРТ НА ПРИСТРІЙ ====================

    /**
     * ВИПРАВЛЕНО: використовує ApplicationState як єдине джерело вибраних книг.
     * Без рефлексії та рекурсивного обходу дерева.
     */
    public void handleExport(BorderPane mainPane) {
        // Отримуємо вибрані книги з ApplicationState
        List<BookViewModel> selectedBooks = collectSelectedBooks();

        if (selectedBooks.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг",
                    "Будь ласка, виберіть книги за допомогою чекбоксів.\n" +
                            "У режимі дерева вибирайте книги на рівні книг (не авторів або серій).\n\n" +
                            "💡 Порада: використовуйте кнопку 'Вибрати всі' для вибору всіх книг.");
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/export-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            ExportController controller = loader.getController();
            controller.setSelectedBooks(selectedBooks);

            Stage dialogStage = new Stage();
            dialogStage.setTitle("Експорт книг (" + selectedBooks.size() + " книг)");
            dialogStage.setScene(new Scene(root, 550, 420));
            dialogStage.initModality(Modality.WINDOW_MODAL);
            dialogStage.initOwner(mainPane.getScene().getWindow());
            controller.setStage(dialogStage);
            dialogStage.showAndWait();

        } catch (Exception e) {
            log.error("Помилка відкриття діалогу експорту", e);
            dialogService.showError("Помилка", "Не вдалося відкрити діалог: " + e.getMessage());
        }
    }

    /**
     * ВИПРАВЛЕНО: єдине джерело вибраних книг — ApplicationState.
     */
    private List<BookViewModel> collectSelectedBooks() {
        // Використовуємо ApplicationState як єдине джерело
        List<BookViewModel> selected = appState.getBookTable().getBooks().stream()
                .filter(BookViewModel::isSelected)
                .collect(Collectors.toList());

        log.info("📊 Знайдено {} вибраних книг через ApplicationState", selected.size());

        if (!selected.isEmpty()) {
            log.info("📋 Список вибраних книг:");
            for (BookViewModel book : selected) {
                log.info("   - {} (ID: {})", book.getTitle(), book.getId());
            }
        }

        return selected;
    }

    // ==================== ЕКСПОРТ В INPX ====================

    public void handleExportInpx(BorderPane mainPane, Runnable onComplete) {
        List<BookViewModel> selectedBooks = collectSelectedBooks();

        List<BookId> bookIds;
        if (!selectedBooks.isEmpty()) {
            bookIds = selectedBooks.stream()
                    .map(b -> BookId.fromString(b.getId()))
                    .collect(Collectors.toList());
        } else {
            if (!dialogService.showConfirmation("Експорт всіх книг",
                    "Жодна книга не вибрана",
                    "Експортувати всі книги колекції?")) {
                return;
            }
            bookIds = null;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Зберегти INPX файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("INPX файли", "*.inpx"));
        File file = fileChooser.showSaveDialog(mainPane.getScene().getWindow());

        if (file == null) {
            return;
        }

        String collectionName = appState.getCurrentLibraryCollection() != null ?
                appState.getCurrentLibraryCollection().getName() : "MyHomeLib Collection";

        InpxExportRequest request = InpxExportRequest.builder()
                .bookIds(bookIds)
                .outputFile(file.toPath())
                .collectionName(collectionName)
                .collectionVersion(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .includeExtraData(true)
                .build();

        dialogService.showInfo("Експорт", "Початок експорту в INPX...");

        new Thread(() -> {
            try {
                ExportToInpxUseCase.ExportResult result = exportToInpxUseCase.execute(request);
                javafx.application.Platform.runLater(() -> {
                    if (result.failed() == 0) {
                        dialogService.showInfo("Успішно", "Експортовано " + result.exported() + " книг в INPX.");
                    } else {
                        dialogService.showError("Помилка", "Не вдалося експортувати: " + result.error());
                    }
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            } catch (Exception e) {
                log.error("Помилка експорту в INPX", e);
                javafx.application.Platform.runLater(() -> {
                    dialogService.showError("Помилка", "Не вдалося експортувати: " + e.getMessage());
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            }
        }).start();
    }

    // ==================== FXML ДІЇ ====================

    @FXML
    private void onChooseDestination() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Виберіть папку для експорту");
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            destinationField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    private void onExport() {
        if (selectedBookIds == null || selectedBookIds.isEmpty()) {
            dialogService.showWarning("Немає книг", "Виберіть книги для експорту.");
            return;
        }

        String destPath = destinationField.getText();
        if (destPath == null || destPath.isBlank()) {
            dialogService.showWarning("Немає папки", "Виберіть папку для експорту.");
            return;
        }

        Path destination = Path.of(destPath);
        if (!destination.toFile().exists()) {
            if (!dialogService.showConfirmation("Створити папку?", "Папка не існує.", "Створити \"" + destPath + "\"?")) {
                return;
            }
        }

        ExportRequest request = ExportRequest.builder()
                .bookIds(selectedBookIds)
                .destinationFolder(destination)
                .format(formatComboBox.getValue())
                .overwriteExisting(overwriteCheckBox.isSelected())
                .extractOnly(extractOnlyCheckBox.isSelected())
                .customFileNameTemplate(fileNameTemplateField.getText().isBlank() ? null : fileNameTemplateField.getText())
                .build();

        exportButton.setDisable(true);
        exportButton.setText("Експортую... (" + selectedBookIds.size() + " книг)");

        new Thread(() -> {
            try {
                ExportToDeviceUseCase.ExportResult result = exportToDeviceUseCase.execute(request);
                javafx.application.Platform.runLater(() -> {
                    exportButton.setDisable(false);
                    exportButton.setText("Експортувати");

                    if (result.failed() == 0) {
                        dialogService.showInfo("Успішно", "Експортовано " + result.exported() + " книг.");
                        closeDialog();
                    } else {
                        String message = String.format("Експортовано %d книг, помилок: %d\n\n%s",
                                result.exported(), result.failed(),
                                String.join("\n", result.errors()));
                        dialogService.showError("Помилка", message);
                    }
                });
            } catch (Exception e) {
                log.error("Помилка експорту", e);
                javafx.application.Platform.runLater(() -> {
                    exportButton.setDisable(false);
                    exportButton.setText("Експортувати");
                    dialogService.showError("Помилка", "Не вдалося виконати експорт: " + e.getMessage());
                });
            }
        }).start();
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    private void closeDialog() {
        if (stage != null) {
            stage.close();
        }
    }
}