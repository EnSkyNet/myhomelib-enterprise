package com.myhomelibcorp.ui.collection;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.collection.CollectionMaintenanceReport;
import com.myhomelibcorp.application.collection.CollectionSourceState;
import com.myhomelibcorp.application.collection.MaintenanceApplyResult;
import com.myhomelibcorp.application.usecase.collection.*;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionWorkspaceController {

    private final LoadCollectionsUseCase loadCollectionsUseCase;
    private final LoadCollectionBooksUseCase loadCollectionBooksUseCase;
    private final IsBookInCollectionUseCase isBookInCollectionUseCase;
    private final AddBookToCollectionUseCase addBookToCollectionUseCase;
    private final RemoveBookFromCollectionUseCase removeBookFromCollectionUseCase;
    private final CreateCollectionUseCase createCollectionUseCase;
    private final RenameCollectionUseCase renameCollectionUseCase;
    private final DeleteCollectionUseCase deleteCollectionUseCase;
    private final CollectionAutoUpdateUseCase collectionAutoUpdateUseCase;
    private final CollectionMaintenanceUseCase collectionMaintenanceUseCase;
    private final NavigationService navigationService;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final BookViewModelMapper bookViewModelMapper;

    @FXML private ListView<CollectionDto> collectionsListView;
    @FXML private Label collectionNameLabel;
    @FXML private Label booksCountLabel;
    @FXML private Label activeCollectionLabel;
    @FXML private Label rootFolderLabel;
    @FXML private Label dbFileLabel;
    @FXML private Label collectionTypeLabel;
    @FXML private TableView<BookViewModel> booksTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> authorColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private Button addBookButton;
    @FXML private Button removeBookButton;
    @FXML private Button renameButton;
    @FXML private Button deleteButton;
    @FXML private Button createButton;
    @FXML private VBox collectionDetailsBox;
    @FXML private TextField sourceFileField;
    @FXML private CheckBox autoUpdateEnabledCheckBox;
    @FXML private Label sourceStatusLabel;
    @FXML private Button sourceCheckButton;
    @FXML private Button maintenanceAnalyzeButton;
    @FXML private Button maintenanceDryRunButton;
    @FXML private Button maintenanceApplyButton;
    @FXML private Label maintenanceStatusLabel;
    @FXML private TextArea maintenanceReportArea;

    private CollectionDto selectedCollection;
    private CollectionDto activeCollection;
    private CollectionMaintenanceReport lastMaintenanceReport;
    private final ObservableList<CollectionDto> collectionList = FXCollections.observableArrayList();
    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());

        booksTableView.setItems(books);

        // ===== НАЛАШТУВАННЯ LISTVIEW ДЛЯ ВІДОБРАЖЕННЯ НАЗВ КОЛЕКЦІЙ =====
        collectionsListView.setItems(collectionList);
        collectionsListView.setCellFactory(lv -> new ListCell<CollectionDto>() {
            @Override
            protected void updateItem(CollectionDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    // Показуємо активну колекцію з позначкою
                    String prefix = item.isActive() ? "● " : "○ ";
                    setText(prefix + item.getName());

                    // Стиль для активної колекції
                    if (item.isActive()) {
                        setStyle("-fx-font-weight: bold; -fx-text-fill: #2196F3;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        collectionsListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                selectedCollection = selected;
                loadCollectionBooks(selected);
                updateCollectionDetails(selected);
                loadSourceState(selected);
                resetMaintenanceView(selected);
                collectionDetailsBox.setVisible(true);
                log.info("Вибрано колекцію: {} (active={})", selected.getName(), selected.isActive());
            } else {
                collectionDetailsBox.setVisible(false);
            }
        });

        booksTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToBook(BookId.fromString(selected.getId()));
                }
            }
        });

        loadCollections();
    }

    public void loadCollections() {
        try {
            List<CollectionDto> collections = loadCollectionsUseCase.execute();
            collectionList.setAll(collections);
            activeCollection = collections.stream().filter(CollectionDto::isActive).findFirst().orElse(null);
            log.info("Завантажено {} колекцій; active={}", collections.size(),
                    activeCollection == null ? "<none>" : activeCollection.getName());
            if (!collections.isEmpty()) {
                CollectionDto toSelect = activeCollection != null ? activeCollection : collections.getFirst();
                collectionsListView.getSelectionModel().select(toSelect);
            } else {
                collectionDetailsBox.setVisible(false);
            }
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій", e);
            dialogService.showError("Помилка", "Не вдалося завантажити колекції: " + e.getMessage());
        }
    }

    private void loadCollectionBooks(CollectionDto collection) {
        List<BookListItem> items = loadCollectionBooksUseCase.execute(collection.getId());
        List<BookViewModel> vms = items.stream()
                .map(bookViewModelMapper::toViewModel)
                .collect(Collectors.toList());
        books.setAll(vms);
        collectionNameLabel.setText(collection.getName());
        booksCountLabel.setText(vms.size() + " книг");
        log.info("Завантажено {} книг для колекції {}", vms.size(), collection.getName());
    }

    private void updateCollectionDetails(CollectionDto collection) {
        if (collection == null) return;
        if (activeCollectionLabel != null) {
            activeCollectionLabel.setText(collection.isActive() ? "Активна колекція: так" : "Активна колекція: ні");
        }
        if (rootFolderLabel != null) {
            rootFolderLabel.setText("Папка: " + displayValue(collection.getRootFolder()));
        }
        if (dbFileLabel != null) {
            dbFileLabel.setText("БД: " + displayValue(collection.getDbFile()));
        }
        if (collectionTypeLabel != null) {
            collectionTypeLabel.setText("Тип: " + switch (collection.getType()) {
                case 1 -> "INPX / архівна";
                case 2 -> "Віддалена / online";
                default -> "Локальна";
            });
        }
        if (renameButton != null) renameButton.setDisable(!collection.isAllowRename());
        if (deleteButton != null) deleteButton.setDisable(!collection.isAllowDelete());
        boolean maintenanceDisabled = !collection.isActive();
        if (maintenanceAnalyzeButton != null) maintenanceAnalyzeButton.setDisable(maintenanceDisabled);
        if (maintenanceDryRunButton != null) maintenanceDryRunButton.setDisable(true);
        if (maintenanceApplyButton != null) maintenanceApplyButton.setDisable(true);
    }

    private String displayValue(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    @FXML
    private void onAddBookToCollection() {
        BookViewModel selectedBook = appState.getBookTable().getSelectedBook();
        if (selectedBook == null) {
            dialogService.showWarning("Немає книги", "Будь ласка, виберіть книгу в головній таблиці.");
            return;
        }

        if (selectedCollection == null) {
            dialogService.showWarning("Немає колекції", "Будь ласка, виберіть колекцію зліва.");
            return;
        }

        boolean inCollection = isBookInCollectionUseCase.execute(selectedCollection.getId(), selectedBook.getId());
        if (inCollection) {
            dialogService.showWarning("Вже є", "Ця книга вже в колекції \"" + selectedCollection.getName() + "\".");
            return;
        }

        try {
            addBookToCollectionUseCase.execute(selectedCollection.getId(), selectedBook.getId());
            loadCollectionBooks(selectedCollection);
            dialogService.showInfo("Успішно", "Книгу додано до колекції \"" + selectedCollection.getName() + "\".");
        } catch (Exception e) {
            log.error("Помилка додавання книги до колекції", e);
            dialogService.showError("Помилка", "Не вдалося додати книгу: " + e.getMessage());
        }
    }

    @FXML
    private void onRemoveBookFromCollection() {
        if (selectedCollection == null) {
            dialogService.showError("Помилка", "Виберіть колекцію");
            return;
        }
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть книгу в таблиці");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити книгу з колекції \"" + selectedCollection.getName() + "\"?");
        confirm.setContentText(selected.getTitle());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                removeBookFromCollectionUseCase.execute(selectedCollection.getId(), selected.getId());
                books.remove(selected);
                booksCountLabel.setText(books.size() + " книг");
                dialogService.showInfo("Успішно", "Книгу видалено з колекції");
            } catch (Exception e) {
                log.error("Помилка видалення книги з колекції", e);
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onCreateCollection() {
        Optional<String> result = dialogService.showTextInput(
                "Створити колекцію",
                "Введіть назву нової колекції",
                "Назва:",
                "");
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    CreateCollectionRequest request = CreateCollectionRequest.builder()
                            .name(name)
                            .importOnCreate(true)
                            .createIndex(true)
                            .build();

                    com.myhomelibcorp.domain.model.collection.Collection collection =
                            createCollectionUseCase.execute(request);

                    CollectionDto dto = CollectionDto.builder()
                            .id(collection.getId())
                            .name(collection.getName())
                            .active(false)
                            .allowRename(true)
                            .allowDelete(true)
                            .rootFolder(collection.getRootFolder() == null ? null : collection.getRootFolder().toString())
                            .dbFile(collection.getDbFile())
                            .type(collection.getType())
                            .booksCount(-1L)
                            .build();
                    collectionList.add(dto);
                    collectionsListView.getSelectionModel().select(dto);
                    dialogService.showInfo("Успішно", "Колекцію \"" + name + "\" створено");
                    log.info("Колекцію створено: id={}, name={}", collection.getId(), collection.getName());
                } catch (Exception e) {
                    log.error("Помилка створення колекції", e);
                    dialogService.showError("Помилка", "Не вдалося створити колекцію: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void onRenameCollection() {
        CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть колекцію");
            return;
        }
        if (!selected.isAllowRename()) {
            dialogService.showError("Помилка", "Цю колекцію не можна перейменовувати");
            return;
        }
        Optional<String> result = dialogService.showTextInput(
                "Перейменувати колекцію",
                "Введіть нову назву для \"" + selected.getName() + "\"",
                "Нова назва:",
                selected.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                try {
                    com.myhomelibcorp.domain.model.collection.Collection renamed =
                            renameCollectionUseCase.execute(selected.getId(), newName);
                    CollectionDto updated = CollectionDto.builder()
                            .id(renamed.getId())
                            .name(renamed.getName())
                            .active(selected.isActive())
                            .allowRename(selected.isAllowRename())
                            .allowDelete(selected.isAllowDelete())
                            .rootFolder(renamed.getRootFolder() == null ? null : renamed.getRootFolder().toString())
                            .dbFile(renamed.getDbFile())
                            .type(renamed.getType())
                            .booksCount(selected.getBooksCount())
                            .build();
                    int index = collectionList.indexOf(selected);
                    if (index >= 0) {
                        collectionList.set(index, updated);
                    }
                    collectionsListView.getSelectionModel().select(updated);
                    dialogService.showInfo("Успішно", "Колекцію перейменовано на \"" + newName + "\"");
                } catch (Exception e) {
                    log.error("Помилка перейменування колекції", e);
                    dialogService.showError("Помилка", "Не вдалося перейменувати: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void onDeleteCollection() {
        CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть колекцію");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Системну колекцію не можна видалити");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити колекцію \"" + selected.getName() + "\"?");
        confirm.setContentText("Книги не будуть видалені, лише зв'язки.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                collectionAutoUpdateUseCase.stop(selected.getId());
                deleteCollectionUseCase.execute(selected.getId());
                collectionList.remove(selected);
                collectionDetailsBox.setVisible(false);
                dialogService.showInfo("Успішно", "Колекцію видалено");
            } catch (Exception e) {
                log.error("Помилка видалення колекції", e);
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    private void loadSourceState(CollectionDto collection) {
        if (sourceFileField == null || collection == null) return;
        Optional<CollectionSourceState> state = collectionAutoUpdateUseCase.load(collection.getId());
        if (state.isEmpty()) {
            sourceFileField.clear();
            autoUpdateEnabledCheckBox.setSelected(false);
            sourceStatusLabel.setText("Джерело автооновлення не налаштовано");
            return;
        }
        renderSourceState(state.get());
    }

    private void renderSourceState(CollectionSourceState state) {
        if (sourceFileField != null) sourceFileField.setText(state.sourceFile() == null ? "" : state.sourceFile().toString());
        if (autoUpdateEnabledCheckBox != null) autoUpdateEnabledCheckBox.setSelected(state.enabled());
        if (sourceStatusLabel != null) {
            String prefix = state.updateAvailable() ? "⚠ Доступне оновлення" : "✓ Без нових змін";
            sourceStatusLabel.setText(prefix + " · " + displaySourceStatus(state.status()));
        }
    }

    private String displaySourceStatus(String status) {
        if (status == null) return "стан невідомий";
        if (status.equals("READY")) return "джерело доступне";
        if (status.equals("APPLIED")) return "оновлення застосовано";
        if (status.equals("SOURCE_MISSING")) return "файл джерела не знайдено";
        if (status.equals("SOURCE_NOT_READABLE")) return "файл джерела недоступний для читання";
        if (status.equals("SOURCE_DIRECTORY_MISSING")) return "каталог джерела не існує";
        if (status.startsWith("SOURCE_ARCHIVE_INVALID")) return "пошкоджений INPX/ZIP";
        return status;
    }

    @FXML
    private void onBrowseSource() {
        if (selectedCollection == null) {
            dialogService.showWarning("Колекція", "Спочатку виберіть колекцію.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Виберіть локальний INPX/ZIP для автооновлення");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("INPX / ZIP", "*.inpx", "*.zip"),
                new FileChooser.ExtensionFilter("Усі файли", "*.*"));
        if (sourceFileField != null && !sourceFileField.getText().isBlank()) {
            try {
                Path current = Paths.get(sourceFileField.getText()).toAbsolutePath().normalize();
                if (current.getParent() != null && Files.isDirectory(current.getParent())) {
                    chooser.setInitialDirectory(current.getParent().toFile());
                }
            } catch (Exception ignored) { }
        }
        var window = collectionsListView.getScene() == null ? null : collectionsListView.getScene().getWindow();
        java.io.File selected = chooser.showOpenDialog(window);
        if (selected != null) sourceFileField.setText(selected.toPath().toAbsolutePath().normalize().toString());
    }

    @FXML
    private void onSaveSourceMonitor() {
        if (selectedCollection == null) return;
        String source = sourceFileField == null ? "" : sourceFileField.getText();
        if (source == null || source.isBlank()) {
            dialogService.showWarning("Автооновлення", "Вкажіть локальний INPX/ZIP source-файл.");
            return;
        }
        setSourceControlsBusy(true);
        collectionAutoUpdateUseCase.configure(
                        selectedCollection.getId(), Paths.get(source), autoUpdateEnabledCheckBox.isSelected())
                .whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
                    setSourceControlsBusy(false);
                    if (error != null) {
                        dialogService.showError("Автооновлення", rootMessage(error));
                    } else {
                        renderSourceState(state);
                        appState.getStatusBar().setStatusText("Налаштування автооновлення збережено");
                    }
                }));
    }

    @FXML
    private void onCheckSourceNow() {
        if (selectedCollection == null) return;
        if (collectionAutoUpdateUseCase.load(selectedCollection.getId()).isEmpty()) {
            dialogService.showWarning("Автооновлення", "Спочатку збережіть source-файл.");
            return;
        }
        setSourceControlsBusy(true);
        collectionAutoUpdateUseCase.checkNow(selectedCollection.getId())
                .whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
                    setSourceControlsBusy(false);
                    if (error != null) dialogService.showError("Перевірка джерела", rootMessage(error));
                    else renderSourceState(state);
                }));
    }

    private void setSourceControlsBusy(boolean busy) {
        if (sourceCheckButton != null) sourceCheckButton.setDisable(busy);
        if (sourceStatusLabel != null && busy) sourceStatusLabel.setText("Перевірка source-файлу...");
    }

    private void resetMaintenanceView(CollectionDto collection) {
        lastMaintenanceReport = null;
        if (maintenanceReportArea != null) maintenanceReportArea.clear();
        if (maintenanceStatusLabel != null) {
            maintenanceStatusLabel.setText(collection != null && collection.isActive()
                    ? "Аналіз ще не запускався" : "Maintenance доступний тільки для активної колекції");
        }
        if (maintenanceDryRunButton != null) maintenanceDryRunButton.setDisable(true);
        if (maintenanceApplyButton != null) maintenanceApplyButton.setDisable(true);
    }

    @FXML
    private void onAnalyzeMaintenance() {
        if (!requireActiveSelectionForMaintenance()) return;
        setMaintenanceBusy(true, "Аналіз файлів, архівів і БД...");
        collectionMaintenanceUseCase.analyze(selectedCollection.getId())
                .whenComplete((report, error) -> UiExecutor.runOnUiThread(() -> {
                    setMaintenanceBusy(false, null);
                    if (error != null) {
                        dialogService.showError("Maintenance", rootMessage(error));
                        return;
                    }
                    lastMaintenanceReport = report;
                    renderMaintenanceReport(report);
                    boolean canRepair = report.repairableSamples() > 0;
                    maintenanceDryRunButton.setDisable(!canRepair);
                    maintenanceApplyButton.setDisable(!canRepair);
                }));
    }

    @FXML
    private void onDryRunMaintenance() {
        if (!requireActiveSelectionForMaintenance() || lastMaintenanceReport == null) return;
        Set<String> ids = repairableIssueIds(lastMaintenanceReport);
        setMaintenanceBusy(true, "Dry run: перевірка плану без змін...");
        collectionMaintenanceUseCase.dryRun(selectedCollection.getId(), ids)
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    setMaintenanceBusy(false, null);
                    if (error != null) dialogService.showError("Dry run", rootMessage(error));
                    else {
                        maintenanceStatusLabel.setText("Dry run завершено: заплановано " + result.requested()
                                + ", пропущено " + result.skipped() + ". Дані не змінено.");
                    }
                }));
    }

    @FXML
    private void onApplyMaintenance() {
        if (!requireActiveSelectionForMaintenance() || lastMaintenanceReport == null) return;
        Set<String> ids = repairableIssueIds(lastMaintenanceReport);
        if (ids.isEmpty()) return;
        boolean confirmed = dialogService.showConfirmation(
                "Застосувати maintenance",
                "Буде застосовано до " + ids.size() + " перевірених проблем",
                "Перед будь-якими змінами автоматично створюється повна резервна копія SQLite. "
                        + "Відсутні/пошкоджені локальні файли лише позначаються як не локальні; "
                        + "фізичні orphan-файли автоматично не видаляються.");
        if (!confirmed) return;
        setMaintenanceBusy(true, "Створення backup і застосування repair...");
        collectionMaintenanceUseCase.apply(selectedCollection.getId(), ids)
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    setMaintenanceBusy(false, null);
                    if (error != null) {
                        dialogService.showError("Maintenance", rootMessage(error));
                        return;
                    }
                    lastMaintenanceReport = result.after();
                    renderMaintenanceReport(result.after());
                    maintenanceStatusLabel.setText("Repair завершено: виправлено " + result.applied()
                            + ", пропущено " + result.skipped() + ". Backup: " + result.backupFile());
                    maintenanceDryRunButton.setDisable(result.after().repairableSamples() == 0);
                    maintenanceApplyButton.setDisable(result.after().repairableSamples() == 0);
                    dialogService.showInfo("Maintenance завершено", "Backup: " + result.backupFile());
                }));
    }

    private boolean requireActiveSelectionForMaintenance() {
        if (selectedCollection == null || !selectedCollection.isActive()) {
            dialogService.showWarning("Maintenance", "Аналіз і repair можна виконувати тільки для активної колекції.");
            return false;
        }
        return true;
    }

    private Set<String> repairableIssueIds(CollectionMaintenanceReport report) {
        return report.issues().stream().filter(i -> i.repairable()).map(i -> i.issueId())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private void setMaintenanceBusy(boolean busy, String text) {
        if (maintenanceAnalyzeButton != null) maintenanceAnalyzeButton.setDisable(busy || selectedCollection == null || !selectedCollection.isActive());
        if (maintenanceDryRunButton != null) maintenanceDryRunButton.setDisable(busy || lastMaintenanceReport == null || lastMaintenanceReport.repairableSamples() == 0);
        if (maintenanceApplyButton != null) maintenanceApplyButton.setDisable(busy || lastMaintenanceReport == null || lastMaintenanceReport.repairableSamples() == 0);
        if (maintenanceStatusLabel != null && text != null) maintenanceStatusLabel.setText(text);
        appState.getStatusBar().setProgressVisible(busy);
        if (busy) appState.getStatusBar().setProgress(-1);
        else appState.getStatusBar().setProgressVisible(false);
    }

    private void renderMaintenanceReport(CollectionMaintenanceReport report) {
        if (maintenanceStatusLabel != null) {
            maintenanceStatusLabel.setText(report.hasIssues()
                    ? "Знайдено проблем: " + report.totalIssues()
                    : "Проблем не знайдено");
        }
        if (maintenanceReportArea == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("SQLite: ").append(report.databaseIntegrityOk() ? "OK" : report.databaseIntegrityMessage()).append('\n');
        sb.append("Перевірено локальних книг: ").append(report.scannedBooks()).append('\n');
        sb.append("Перевірено фізичних файлів: ").append(report.scannedFiles()).append('\n');
        sb.append("Відсутніх файлів: ").append(report.missingFiles()).append('\n');
        sb.append("Некоректних archive references: ").append(report.invalidArchiveReferences()).append('\n');
        sb.append("Orphan-файлів: ").append(report.orphanFiles()).append(" (автоматично не видаляються)\n");
        sb.append("Авторів без книг: ").append(report.orphanedAuthors()).append('\n');
        sb.append("Жанрів без книг: ").append(report.orphanedGenres()).append('\n');
        sb.append("Точних дублікатів storage+LibID: ").append(report.duplicateBooks()).append('\n');
        if (report.samplesTruncated()) sb.append("\n⚠ Список нижче семплований; повторіть аналіз після repair для наступної порції.\n");
        sb.append("\n--- Preview ---\n");
        report.issues().stream().limit(200).forEach(issue -> sb.append(issue.repairable() ? "[repair] " : "[report] ")
                .append(issue.description()).append('\n'));
        if (report.issues().size() > 200) sb.append("... ще ").append(report.issues().size() - 200).append(" семплів\n");
        maintenanceReportArea.setText(sb.toString());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @FXML
    private void onRefresh() {
        loadCollections();
        dialogService.showInfo("Оновлення", "Колекції перезавантажено.");
    }

    public void refresh() {
        loadCollections();
    }
}