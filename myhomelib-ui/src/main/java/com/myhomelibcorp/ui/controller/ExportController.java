package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.dto.InpxExportRequest;
import com.myhomelibcorp.application.export.ExportCollisionContext;
import com.myhomelibcorp.application.export.ExportCollisionDecision;
import com.myhomelibcorp.application.export.ExportHistoryEntry;
import com.myhomelibcorp.application.export.ExportHistoryService;
import com.myhomelibcorp.application.export.ExportProfile;
import com.myhomelibcorp.application.export.ExportProfileService;
import com.myhomelibcorp.application.usecase.export.ExportToDeviceUseCase;
import com.myhomelibcorp.application.usecase.export.ExportToInpxUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExportController {

    private final ExportToDeviceUseCase exportToDeviceUseCase;
    private final ExportToInpxUseCase exportToInpxUseCase;
    private final ExportProfileService exportProfileService;
    private final ExportHistoryService exportHistoryService;
    private final BookActionProfileService bookActionProfileService;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final ApplicationContext springContext;

    @FXML private ComboBox<ExportProfile> profileComboBox;
    @FXML private ComboBox<ExportRequest.ExportFormat> formatComboBox;
    @FXML private TextField destinationField;
    @FXML private TextField fileNameTemplateField;
    @FXML private TextField subfolderTemplateField;
    @FXML private ComboBox<ExportRequest.CollisionPolicy> collisionPolicyComboBox;
    @FXML private ComboBox<ActionChoice> postActionComboBox;
    @FXML private CheckBox extractOnlyCheckBox;
    @FXML private Label selectedBooksLabel;
    @FXML private Button exportButton;
    @FXML private Button cancelButton;
    @FXML private Button updateProfileButton;
    @FXML private Button deleteProfileButton;

    private List<BookId> selectedBookIds = List.of();
    private Stage stage;
    private final AtomicBoolean exportCancelFlag = new AtomicBoolean(false);
    private volatile boolean exportRunning;
    private boolean applyingProfile;

    @FXML
    public void initialize() {
        Set<ExportRequest.ExportFormat> supported = exportToDeviceUseCase.supportedFormats();
        formatComboBox.getItems().setAll(java.util.Arrays.stream(ExportRequest.ExportFormat.values())
                .filter(supported::contains).toList());
        if (!formatComboBox.getItems().isEmpty()) formatComboBox.setValue(formatComboBox.getItems().getFirst());

        collisionPolicyComboBox.getItems().setAll(
                ExportRequest.CollisionPolicy.RENAME,
                ExportRequest.CollisionPolicy.OVERWRITE,
                ExportRequest.CollisionPolicy.SKIP,
                ExportRequest.CollisionPolicy.ASK);
        collisionPolicyComboBox.setValue(ExportRequest.CollisionPolicy.RENAME);
        collisionPolicyComboBox.setConverter(new StringConverter<>() {
            @Override public String toString(ExportRequest.CollisionPolicy p) {
                if (p == null) return "";
                return switch (p) {
                    case RENAME -> "Перейменувати новий файл";
                    case OVERWRITE -> "Перезаписати існуючий";
                    case SKIP -> "Пропустити існуючий";
                    case ASK -> "Запитувати для кожного конфлікту";
                };
            }
            @Override public ExportRequest.CollisionPolicy fromString(String value) { return collisionPolicyComboBox.getValue(); }
        });

        profileComboBox.setConverter(new StringConverter<>() {
            @Override public String toString(ExportProfile p) { return p == null ? "" : p.name(); }
            @Override public ExportProfile fromString(String value) { return profileComboBox.getValue(); }
        });
        profileComboBox.valueProperty().addListener((obs, old, value) -> { if (!applyingProfile) applyProfile(value); });
        loadPostActions("");
        loadProfiles(null);

        selectedBooksLabel.setText("0 книг вибрано");
        exportButton.setDisable(true);
        updateProfileButtons();
    }

    public void setSelectedBooks(List<BookViewModel> selectedBooks) {
        if (selectedBooks == null || selectedBooks.isEmpty()) {
            selectedBookIds = List.of();
            selectedBooksLabel.setText("0 книг вибрано");
            exportButton.setDisable(true);
            return;
        }

        List<BookViewModel> validBooks = selectedBooks.stream()
                .filter(b -> b.getId() != null && !b.getId().isBlank()).toList();
        selectedBookIds = validBooks.stream().map(b -> BookId.fromString(b.getId())).collect(Collectors.toList());
        EnumSet<ExportRequest.ExportFormat> common = EnumSet.noneOf(ExportRequest.ExportFormat.class);
        common.addAll(exportToDeviceUseCase.supportedFormats());
        for (BookViewModel book : validBooks) common.retainAll(sourceFormats(book));
        ExportRequest.ExportFormat preferred = formatComboBox.getValue();
        formatComboBox.getItems().setAll(java.util.Arrays.stream(ExportRequest.ExportFormat.values())
                .filter(common::contains).toList());
        selectedBooksLabel.setText(validBooks.size() + " книг вибрано");
        if (validBooks.isEmpty() || common.isEmpty()) {
            formatComboBox.setValue(null);
            exportButton.setDisable(true);
            if (!validBooks.isEmpty()) selectedBooksLabel.setText(validBooks.size() + " книг — немає спільного формату експорту");
        } else {
            ExportProfile profile = profileComboBox.getValue();
            if (profile != null && common.contains(profile.format())) formatComboBox.setValue(profile.format());
            else if (preferred != null && common.contains(preferred)) formatComboBox.setValue(preferred);
            else formatComboBox.setValue(formatComboBox.getItems().getFirst());
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
            dialogStage.setScene(new Scene(root, 700, 620));
            dialogStage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) dialogStage.initOwner(owner);
            controller.setStage(dialogStage);
            dialogStage.showAndWait();
        } catch (Exception e) {
            log.error("Помилка відкриття діалогу експорту", e);
            dialogService.showError("Помилка", "Не вдалося відкрити діалог: " + e.getMessage());
        }
    }

    private List<BookViewModel> collectSelectedBooks() {
        List<BookViewModel> selected = appState.getBookTable().getBooks().stream()
                .filter(BookViewModel::isSelected).collect(Collectors.toList());
        log.info("Знайдено {} вибраних книг через ApplicationState", selected.size());
        return selected;
    }

    // ==================== ЕКСПОРТ В INPX ====================

    public void handleExportInpx(BorderPane mainPane, Runnable onComplete) {
        List<BookViewModel> selectedBooks = collectSelectedBooks();
        List<BookId> bookIds;
        if (!selectedBooks.isEmpty()) {
            bookIds = selectedBooks.stream().map(b -> BookId.fromString(b.getId())).collect(Collectors.toList());
        } else {
            if (!dialogService.showConfirmation("Експорт всіх книг", "Жодна книга не вибрана", "Експортувати всі книги колекції?")) return;
            bookIds = null;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Зберегти INPX файл");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("INPX файли", "*.inpx"));
        File file = fileChooser.showSaveDialog(mainPane.getScene().getWindow());
        if (file == null) return;

        String collectionName = appState.getCurrentLibraryCollection() != null
                ? appState.getCurrentLibraryCollection().getName() : "MyHomeLib Collection";
        InpxExportRequest request = InpxExportRequest.builder()
                .bookIds(bookIds).outputFile(file.toPath()).collectionName(collectionName)
                .collectionVersion(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .includeExtraData(true).build();

        dialogService.showInfo("Експорт", "Початок експорту в INPX...");
        Thread worker = new Thread(() -> {
            try {
                ExportToInpxUseCase.ExportResult result = exportToInpxUseCase.execute(request);
                Platform.runLater(() -> {
                    if (result.failed() == 0) dialogService.showInfo("Успішно", "Експортовано " + result.exported() + " книг в INPX.");
                    else dialogService.showError("Помилка", "Не вдалося експортувати: " + result.error());
                    if (onComplete != null) onComplete.run();
                });
            } catch (Exception e) {
                log.error("Помилка експорту в INPX", e);
                Platform.runLater(() -> {
                    dialogService.showError("Помилка", "Не вдалося експортувати: " + e.getMessage());
                    if (onComplete != null) onComplete.run();
                });
            }
        }, "myhomelib-inpx-export");
        worker.setDaemon(true); worker.start();
    }

    // ==================== STAGE 16 PROFILES / HISTORY ====================

    @FXML private void onSaveProfileAs() {
        if (formatComboBox.getValue() == null) { dialogService.showWarning("Профіль", "Спочатку виберіть сумісний формат."); return; }
        TextInputDialog dialog = new TextInputDialog(profileComboBox.getValue() == null ? "Новий профіль" : profileComboBox.getValue().name());
        dialog.setTitle("Зберегти export profile");
        dialog.setHeaderText("Назва нового профілю");
        if (stage != null) dialog.initOwner(stage);
        dialog.showAndWait().map(String::trim).filter(s -> !s.isBlank()).ifPresent(name -> {
            ExportProfile created = exportProfileService.newProfile(name);
            ExportProfile saved = snapshotProfile(created.id(), name);
            exportProfileService.save(saved);
            loadProfiles(saved.id());
        });
    }

    @FXML private void onUpdateProfile() {
        ExportProfile current = profileComboBox.getValue();
        if (current == null) return;
        if (formatComboBox.getValue() == null) { dialogService.showWarning("Профіль", "Спочатку виберіть сумісний формат."); return; }
        ExportProfile saved = snapshotProfile(current.id(), current.name());
        exportProfileService.save(saved);
        loadProfiles(saved.id());
    }

    @FXML private void onDeleteProfile() {
        ExportProfile current = profileComboBox.getValue();
        if (current == null) return;
        if (!dialogService.showConfirmation("Видалити профіль", current.name(), "Видалити цей export profile?")) return;
        exportProfileService.delete(current.id());
        loadProfiles(null);
    }

    @FXML private void onShowHistory() {
        List<ExportHistoryEntry> history = exportHistoryService.loadRecent(30);
        StringBuilder text = new StringBuilder();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        if (history.isEmpty()) text.append("Історія експорту порожня.");
        for (ExportHistoryEntry e : history) {
            text.append(fmt.format(e.completedAt())).append("  ")
                    .append(e.profileName().isBlank() ? "Ad hoc" : e.profileName()).append("  ")
                    .append(e.format() == null ? "?" : e.format()).append('\n')
                    .append("  requested=").append(e.requested())
                    .append(" exported=").append(e.exported())
                    .append(" skipped=").append(e.skipped())
                    .append(" failed=").append(e.failed())
                    .append(e.cancelled() ? " CANCELLED" : "")
                    .append("  time=").append(formatDuration(e.durationMs())).append('\n')
                    .append("  ").append(e.destination()).append("\n\n");
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Історія експорту"); alert.setHeaderText("Останні " + history.size() + " запусків");
        if (stage != null) alert.initOwner(stage);
        TextArea area = new TextArea(text.toString()); area.setEditable(false); area.setWrapText(false); area.setPrefSize(760, 440);
        alert.getDialogPane().setContent(area); alert.showAndWait();
    }

    private ExportProfile snapshotProfile(String id, String name) {
        return new ExportProfile(id, name, formatComboBox.getValue(), text(destinationField.getText()),
                collisionPolicyComboBox.getValue(), extractOnlyCheckBox.isSelected(),
                text(fileNameTemplateField.getText()), text(subfolderTemplateField.getText()), selectedPostActionId());
    }

    private void loadProfiles(String selectId) {
        List<ExportProfile> profiles = exportProfileService.loadProfiles();
        applyingProfile = true;
        try {
            profileComboBox.getItems().setAll(profiles);
            ExportProfile selected = profiles.stream().filter(p -> p.id().equals(selectId)).findFirst()
                    .orElse(profiles.isEmpty() ? null : profiles.getFirst());
            profileComboBox.setValue(selected);
            if (selected != null) applyProfile(selected);
        } finally { applyingProfile = false; }
        updateProfileButtons();
    }

    private void applyProfile(ExportProfile profile) {
        if (profile == null) { updateProfileButtons(); return; }
        applyingProfile = true;
        try {
            if (formatComboBox.getItems().contains(profile.format())) formatComboBox.setValue(profile.format());
            destinationField.setText(profile.destinationFolder());
            collisionPolicyComboBox.setValue(profile.collisionPolicy());
            extractOnlyCheckBox.setSelected(profile.extractOnly());
            fileNameTemplateField.setText(profile.filenameTemplate());
            subfolderTemplateField.setText(profile.subfolderTemplate());
            selectPostAction(profile.postActionProfileId());
        } finally { applyingProfile = false; }
        updateProfileButtons();
    }

    private void loadPostActions(String selectId) {
        List<ActionChoice> choices = new java.util.ArrayList<>();
        choices.add(new ActionChoice("", "Без post-action"));
        for (BookActionProfile p : bookActionProfileService.loadProfiles()) {
            if (p.enabled() && !p.commands().isEmpty()) choices.add(new ActionChoice(p.id(), p.name()));
        }
        postActionComboBox.getItems().setAll(choices);
        selectPostAction(selectId);
    }

    private void selectPostAction(String id) {
        String target = text(id);
        ActionChoice choice = postActionComboBox.getItems().stream().filter(c -> c.id().equals(target)).findFirst()
                .orElse(postActionComboBox.getItems().isEmpty() ? null : postActionComboBox.getItems().getFirst());
        postActionComboBox.setValue(choice);
    }

    private String selectedPostActionId() {
        ActionChoice value = postActionComboBox.getValue();
        return value == null ? "" : value.id();
    }

    private void updateProfileButtons() {
        boolean none = profileComboBox == null || profileComboBox.getValue() == null || exportRunning;
        if (updateProfileButton != null) updateProfileButton.setDisable(none);
        if (deleteProfileButton != null) deleteProfileButton.setDisable(none);
    }

    // ==================== FXML ACTIONS ====================

    @FXML private void onChooseDestination() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Виберіть папку для експорту");
        File dir = chooser.showDialog(stage);
        if (dir != null) destinationField.setText(dir.getAbsolutePath());
    }

    @FXML private void onExport() {
        if (selectedBookIds == null || selectedBookIds.isEmpty()) {
            dialogService.showWarning("Немає книг", "Виберіть книги для експорту."); return;
        }
        String destPath = text(destinationField.getText());
        if (destPath.isBlank()) { dialogService.showWarning("Немає папки", "Виберіть папку для експорту."); return; }
        if (formatComboBox.getValue() == null) {
            dialogService.showWarning("Немає сумісного формату", "Вибрані книги мають різні вихідні формати. Експортуйте їх окремими групами."); return;
        }
        Path destination;
        try { destination = Path.of(destPath).toAbsolutePath().normalize(); }
        catch (RuntimeException e) { dialogService.showWarning("Некоректна папка", e.getMessage()); return; }
        if (!destination.toFile().exists()
                && !dialogService.showConfirmation("Створити папку?", "Папка не існує.", "Створити \"" + destPath + "\"?")) return;

        ExportProfile selectedProfile = profileComboBox.getValue();
        ExportRequest request = ExportRequest.builder()
                .bookIds(selectedBookIds).destinationFolder(destination).format(formatComboBox.getValue())
                .collisionPolicy(collisionPolicyComboBox.getValue())
                .overwriteExisting(collisionPolicyComboBox.getValue() == ExportRequest.CollisionPolicy.OVERWRITE)
                .extractOnly(extractOnlyCheckBox.isSelected())
                .customFileNameTemplate(text(fileNameTemplateField.getText()))
                .subfolderTemplate(text(subfolderTemplateField.getText()))
                .profileId(selectedProfile == null ? "" : selectedProfile.id())
                .profileName(selectedProfile == null ? "Ad hoc" : selectedProfile.name())
                .postActionProfileId(selectedPostActionId())
                .build();

        setExportRunning(true);
        exportCancelFlag.set(false);
        appState.getStatusBar().setProgressVisible(true);
        appState.getStatusBar().setProgress(0);
        appState.getStatusBar().setStatusText("Експорт: 0/" + selectedBookIds.size());

        Thread worker = new Thread(() -> {
            try {
                ExportToDeviceUseCase.ExportResult result = exportToDeviceUseCase.execute(request, exportCancelFlag, progress ->
                        Platform.runLater(() -> {
                            double value = progress.total() <= 0 ? 0 : (double) progress.processed() / progress.total();
                            selectedBooksLabel.setText(String.format("Експорт: %d/%d — %s", progress.processed(), progress.total(), progress.title()));
                            exportButton.setText(String.format("Експортую... %d/%d", progress.processed(), progress.total()));
                            appState.getStatusBar().setProgress(value);
                            appState.getStatusBar().setStatusText(String.format("Експорт: %d/%d — %s", progress.processed(), progress.total(), progress.title()));
                        }), this::resolveCollision);
                Platform.runLater(() -> finishExport(result));
            } catch (Exception e) {
                log.error("Помилка експорту", e);
                Platform.runLater(() -> {
                    setExportRunning(false);
                    appState.getStatusBar().setProgressVisible(false);
                    appState.getStatusBar().setStatusText("Помилка експорту");
                    dialogService.showError("Помилка", "Не вдалося виконати експорт: " + e.getMessage());
                });
            }
        }, "myhomelib-export");
        worker.setDaemon(true); worker.start();
    }

    private ExportCollisionDecision resolveCollision(ExportCollisionContext context) {
        if (Platform.isFxApplicationThread()) return showCollisionDialog(context);
        CompletableFuture<ExportCollisionDecision> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { answer.complete(showCollisionDialog(context)); }
            catch (RuntimeException e) { answer.complete(ExportCollisionDecision.SKIP); }
        });
        try { return answer.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return ExportCollisionDecision.CANCEL; }
        catch (Exception e) { return ExportCollisionDecision.SKIP; }
    }

    private ExportCollisionDecision showCollisionDialog(ExportCollisionContext context) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Файл вже існує");
        alert.setHeaderText(context.title());
        alert.setContentText(context.existingFile() + "\n\nОберіть дію:");

        ButtonType rename = new ButtonType("Автоматично перейменувати");
        ButtonType overwrite = new ButtonType("Перезаписати");
        ButtonType skip = new ButtonType("Пропустити");
        ButtonType cancel = new ButtonType("Скасувати весь експорт", ButtonBar.ButtonData.CANCEL_CLOSE);

        alert.getButtonTypes().setAll(rename, overwrite, skip, cancel);

        ButtonType result = alert.showAndWait().orElse(cancel);
        if (result == rename) return ExportCollisionDecision.RENAME;
        if (result == overwrite) return ExportCollisionDecision.OVERWRITE;
        if (result == skip) return ExportCollisionDecision.SKIP;
        return ExportCollisionDecision.CANCEL;
    }

    private void finishExport(ExportToDeviceUseCase.ExportResult result) {
        setExportRunning(false);
        appState.getStatusBar().setProgressVisible(false);
        selectedBooksLabel.setText(selectedBookIds.size() + " книг вибрано");
        String summary = String.format("Експортовано: %d, пропущено: %d, помилок: %d, час: %s.",
                result.exported(), result.skipped(), result.failed(), formatDuration(result.durationMs()));
        appState.getStatusBar().setStatusText(result.cancelled() ? "Експорт скасовано — " + summary : "Експорт завершено — " + summary);

        if (result.cancelled()) {
            dialogService.showInfo("Експорт скасовано", summary);
        } else if (result.failed() == 0 && result.errors().isEmpty()) {
            dialogService.showInfo("Успішно", summary);
        } else if (result.failed() == 0) {
            dialogService.showWarning("Експорт завершено з попередженнями", summary + "\n\n" + String.join("\n", result.errors()));
        } else {
            dialogService.showError("Помилка", summary + "\n\n" + String.join("\n", result.errors()));
        }
    }

    private void setExportRunning(boolean running) {
        exportRunning = running;
        exportButton.setDisable(running || selectedBookIds == null || selectedBookIds.isEmpty() || formatComboBox.getValue() == null);
        formatComboBox.setDisable(running); collisionPolicyComboBox.setDisable(running); profileComboBox.setDisable(running);
        destinationField.setDisable(running); fileNameTemplateField.setDisable(running); subfolderTemplateField.setDisable(running);
        postActionComboBox.setDisable(running); extractOnlyCheckBox.setDisable(running);
        cancelButton.setDisable(false);
        cancelButton.setText(running ? "Скасувати експорт" : "Закрити");
        exportButton.setText(running ? "Експортую... (" + selectedBookIds.size() + " книг)" : "Експортувати");
        updateProfileButtons();
    }

    @FXML private void onCancel() {
        if (exportRunning) {
            exportCancelFlag.set(true); cancelButton.setDisable(true); cancelButton.setText("Скасовую..."); return;
        }
        closeDialog();
    }

    private void closeDialog() { if (stage != null) stage.close(); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String formatDuration(long ms) {
        long seconds = Math.max(0, ms) / 1000; return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }
    private record ActionChoice(String id, String name) { @Override public String toString() { return name; } }
}