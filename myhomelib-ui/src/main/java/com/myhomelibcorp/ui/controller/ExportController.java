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
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
    @FXML private ComboBox<ExportRequest.CollisionPolicy> collisionPolicyComboBox;
    @FXML private CheckBox extractOnlyCheckBox;
    @FXML private Label selectedBooksLabel;
    @FXML private Button exportButton;
    @FXML private Button cancelButton;

    private List<BookId> selectedBookIds;
    private Stage stage;
    private final AtomicBoolean exportCancelFlag = new AtomicBoolean(false);
    private volatile boolean exportRunning;

    @FXML
    public void initialize() {
        Set<ExportRequest.ExportFormat> supported = exportToDeviceUseCase.supportedFormats();
        formatComboBox.getItems().setAll(java.util.Arrays.stream(ExportRequest.ExportFormat.values())
                .filter(supported::contains).toList());
        if (!formatComboBox.getItems().isEmpty()) formatComboBox.setValue(formatComboBox.getItems().get(0));
        collisionPolicyComboBox.getItems().setAll(
                ExportRequest.CollisionPolicy.RENAME,
                ExportRequest.CollisionPolicy.OVERWRITE,
                ExportRequest.CollisionPolicy.SKIP);
        collisionPolicyComboBox.setValue(ExportRequest.CollisionPolicy.RENAME);
        collisionPolicyComboBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ExportRequest.CollisionPolicy p) {
                if (p == null) return "";
                return switch (p) {
                    case RENAME -> "Перейменувати новий файл";
                    case OVERWRITE -> "Перезаписати існуючий";
                    case SKIP -> "Пропустити існуючий";
                };
            }
            @Override public ExportRequest.CollisionPolicy fromString(String value) { return collisionPolicyComboBox.getValue(); }
        });
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

        List<BookViewModel> validBooks = selectedBooks.stream()
                .filter(b -> b.getId() != null && !b.getId().isBlank()).toList();
        this.selectedBookIds = validBooks.stream().map(b -> BookId.fromString(b.getId())).collect(Collectors.toList());
        EnumSet<ExportRequest.ExportFormat> common = EnumSet.noneOf(ExportRequest.ExportFormat.class);
        common.addAll(exportToDeviceUseCase.supportedFormats());
        for (BookViewModel book : validBooks) common.retainAll(sourceFormats(book));
        formatComboBox.getItems().setAll(java.util.Arrays.stream(ExportRequest.ExportFormat.values())
                .filter(common::contains).toList());
        selectedBooksLabel.setText(validBooks.size() + " книг вибрано");
        if (validBooks.isEmpty() || common.isEmpty()) {
            formatComboBox.setValue(null);
            exportButton.setDisable(true);
            if (!validBooks.isEmpty()) selectedBooksLabel.setText(validBooks.size() + " книг — немає спільного формату експорту");
        } else {
            formatComboBox.setValue(formatComboBox.getItems().get(0));
            exportButton.setDisable(false);
        }
    }

    private Set<ExportRequest.ExportFormat> sourceFormats(BookViewModel book) {
        String source = book.getArchiveEntry();
        if (source == null || source.isBlank()) source = book.getFileName();
        source = source == null ? "" : source.toLowerCase();
        if (source.endsWith(".fb2") || source.endsWith(".fbd"))
            return EnumSet.of(ExportRequest.ExportFormat.FB2, ExportRequest.ExportFormat.FB2_ZIP, ExportRequest.ExportFormat.TXT);
        if (source.endsWith(".epub")) return EnumSet.of(ExportRequest.ExportFormat.EPUB);
        if (source.endsWith(".txt") || source.endsWith(".text")) return EnumSet.of(ExportRequest.ExportFormat.TXT);
        return EnumSet.noneOf(ExportRequest.ExportFormat.class);
    }

    public void setStage(Stage stage) {
        this.stage = stage;
        if (stage != null) {
            stage.setOnCloseRequest(event -> {
                if (exportRunning) {
                    exportCancelFlag.set(true);
                    event.consume();
                }
            });
        }
    }

    // ==================== ЕКСПОРТ НА ПРИСТРІЙ ====================

    /**
     * ВИПРАВЛЕНО: використовує ApplicationState як єдине джерело вибраних книг.
     * Без рефлексії та рекурсивного обходу дерева.
     */
    public void handleExport(BorderPane mainPane) {
        List<BookViewModel> selectedBooks = collectSelectedBooks();
        if (selectedBooks.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг", "Будь ласка, виберіть книги за допомогою чекбоксів.");
            return;
        }
        showExportDialog(mainPane.getScene().getWindow(), selectedBooks);
    }

    public void showExportDialog(Window owner, List<BookViewModel> selectedBooks) {
        if (selectedBooks == null || selectedBooks.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг", "Виберіть хоча б одну книгу.");
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
            if (owner != null) dialogStage.initOwner(owner);
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

        if (formatComboBox.getValue() == null) {
            dialogService.showWarning("Немає сумісного формату",
                    "Вибрані книги мають різні вихідні формати. Експортуйте їх окремими групами.");
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
                .collisionPolicy(collisionPolicyComboBox.getValue())
                .overwriteExisting(collisionPolicyComboBox.getValue() == ExportRequest.CollisionPolicy.OVERWRITE)
                .extractOnly(extractOnlyCheckBox.isSelected())
                .customFileNameTemplate(fileNameTemplateField.getText().isBlank() ? null : fileNameTemplateField.getText())
                .build();

        exportCancelFlag.set(false);
        exportRunning = true;
        exportButton.setDisable(true);
        collisionPolicyComboBox.setDisable(true);
        cancelButton.setDisable(false);
        cancelButton.setText("Скасувати експорт");
        exportButton.setText("Експортую... (" + selectedBookIds.size() + " книг)");

        Thread worker = new Thread(() -> {
            try {
                ExportToDeviceUseCase.ExportResult result = exportToDeviceUseCase.execute(request, exportCancelFlag, progress ->
                        javafx.application.Platform.runLater(() -> {
                            selectedBooksLabel.setText(String.format("Експорт: %d/%d — %s",
                                    progress.processed(), progress.total(), progress.title()));
                            exportButton.setText(String.format("Експортую... %d/%d", progress.processed(), progress.total()));
                        }));
                javafx.application.Platform.runLater(() -> {
                    exportRunning = false;
                    exportButton.setDisable(false);
                    collisionPolicyComboBox.setDisable(false);
                    cancelButton.setDisable(false);
                    cancelButton.setText("Скасувати");
                    exportButton.setText("Експортувати");
                    selectedBooksLabel.setText(selectedBookIds.size() + " книг вибрано");

                    if (result.cancelled()) {
                        dialogService.showInfo("Експорт скасовано", String.format(
                                "Експортовано: %d, пропущено: %d, помилок: %d.",
                                result.exported(), result.skipped(), result.failed()));
                    } else if (result.failed() == 0) {
                        dialogService.showInfo("Успішно", String.format(
                                "Експортовано %d книг, пропущено %d.", result.exported(), result.skipped()));
                        closeDialog();
                    } else {
                        String message = String.format("Експортовано %d, пропущено %d, помилок: %d\n\n%s",
                                result.exported(), result.skipped(), result.failed(), String.join("\n", result.errors()));
                        dialogService.showError("Помилка", message);
                    }
                });
            } catch (Exception e) {
                log.error("Помилка експорту", e);
                javafx.application.Platform.runLater(() -> {
                    exportRunning = false;
                    exportButton.setDisable(false);
                    collisionPolicyComboBox.setDisable(false);
                    cancelButton.setDisable(false);
                    cancelButton.setText("Скасувати");
                    exportButton.setText("Експортувати");
                    dialogService.showError("Помилка", "Не вдалося виконати експорт: " + e.getMessage());
                });
            }
        }, "myhomelib-export");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onCancel() {
        if (exportRunning) {
            exportCancelFlag.set(true);
            cancelButton.setDisable(true);
            cancelButton.setText("Скасовую...");
            return;
        }
        closeDialog();
    }

    private void closeDialog() {
        if (stage != null) {
            stage.close();
        }
    }
}