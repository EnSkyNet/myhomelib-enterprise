package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.port.out.validation.CollectionValidatorPort;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.usecase.collection.CreateCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.application.usecase.series.SyncSeriesUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.CollectionWizardViewModel;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionWizardController {

    private final CreateCollectionUseCase createCollectionUseCase;
    private final CollectionValidatorPort collectionValidator;
    private final DialogService dialogService;
    private final FileChooserService fileChooserService;
    private final SwitchCollectionUseCase switchCollectionUseCase;
    private final StatisticsService statisticsService;
    private final SyncSeriesUseCase syncSeriesUseCase;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationState appState;

    private final CollectionWizardViewModel model = new CollectionWizardViewModel();

    @FXML private TextField nameField;
    @FXML private TextField rootFolderField;
    @FXML private TextField dbPathField;
    @FXML private ComboBox<CollectionType> typeComboBox;
    @FXML private TextField sourcePathField;
    @FXML private TextField urlField;
    @FXML private TextField userField;
    @FXML private PasswordField passwordField;
    @FXML private CheckBox importOnCreateCheck;
    @FXML private CheckBox createIndexCheck;
    @FXML private Button nextButton;
    @FXML private Button backButton;
    @FXML private Button finishButton;
    @FXML private Label errorLabel;
    @FXML private StackPane wizardContent;
    @FXML private Label confirmName;
    @FXML private Label confirmType;
    @FXML private Label confirmRootFolder;
    @FXML private Label confirmDbFile;
    @FXML private Label confirmSourcePath;
    @FXML private Label confirmImportOnCreate;
    @FXML private Label confirmCreateIndex;
    @FXML private Label errorLabel2;

    private Stage stage;
    private Runnable onComplete;
    private ObservableList<CollectionDto> collectionList;

    @FXML
    public void initialize() {
        log.info("CollectionWizardController ініціалізовано");

        typeComboBox.getItems().setAll(CollectionType.values());
        typeComboBox.setValue(CollectionType.FB2_LOCAL);

        typeComboBox.setConverter(new javafx.util.StringConverter<CollectionType>() {
            @Override
            public String toString(CollectionType type) {
                return type == null ? "" : type.getDisplayName();
            }
            @Override
            public CollectionType fromString(String string) {
                return CollectionType.FB2_LOCAL;
            }
        });

        nameField.textProperty().bindBidirectional(model.nameProperty());
        rootFolderField.textProperty().bindBidirectional(
                model.rootFolderProperty(),
                new javafx.util.StringConverter<>() {
                    @Override
                    public String toString(Path path) { return path != null ? path.toString() : ""; }
                    @Override
                    public Path fromString(String string) { return string != null && !string.isBlank() ? Paths.get(string) : null; }
                }
        );
        dbPathField.textProperty().bindBidirectional(
                model.dbFileProperty(),
                new javafx.util.StringConverter<>() {
                    @Override
                    public String toString(Path path) { return path != null ? path.toString() : ""; }
                    @Override
                    public Path fromString(String string) { return string != null && !string.isBlank() ? Paths.get(string) : null; }
                }
        );
        sourcePathField.textProperty().bindBidirectional(model.sourcePathProperty());
        urlField.textProperty().bindBidirectional(model.urlProperty());
        userField.textProperty().bindBidirectional(model.userProperty());
        passwordField.textProperty().bindBidirectional(model.passwordProperty());
        typeComboBox.valueProperty().bindBidirectional(model.typeProperty());
        importOnCreateCheck.selectedProperty().bindBidirectional(model.importOnCreateProperty());
        createIndexCheck.selectedProperty().bindBidirectional(model.createIndexProperty());

        nameField.textProperty().addListener((obs, old, val) -> validate());
        rootFolderField.textProperty().addListener((obs, old, val) -> validate());
        dbPathField.textProperty().addListener((obs, old, val) -> validate());
        sourcePathField.textProperty().addListener((obs, old, val) -> validate());
        urlField.textProperty().addListener((obs, old, val) -> validate());

        validate();
        updateStep(0);
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public void setOnComplete(Runnable onComplete) {
        this.onComplete = onComplete;
    }

    public void setCollectionList(ObservableList<CollectionDto> collectionList) {
        this.collectionList = collectionList;
    }

    @FXML
    private void onNext() {
        int current = model.getCurrentStep();
        if (current < 2) {
            if (model.isStepValid(current)) {
                updateStep(current + 1);
            } else {
                showError("Заповніть всі обов'язкові поля");
            }
        }
    }

    @FXML
    private void onBack() {
        int current = model.getCurrentStep();
        if (current > 0) {
            updateStep(current - 1);
        }
    }

    @FXML
    private void onFinish() {
        if (!model.isValid()) {
            showError("Будь ласка, заповніть всі обов'язкові поля");
            return;
        }

        CreateCollectionRequest request = buildRequest();
        List<String> errors = collectionValidator.validate(request);
        if (!errors.isEmpty()) {
            showError(String.join("\n", errors));
            return;
        }

        finishButton.setDisable(true);
        finishButton.setText("Створення...");
        appState.getStatusBar().setStatusText("Створення колекції...");

        new Thread(() -> {
            try {
                log.info("Початок створення колекції: {}", request.getName());

                Collection collection = createCollectionUseCase.execute(request);
                log.info("Колекцію створено: id={}, name={}, dbFile={}",
                        collection.getId(), collection.getName(), collection.getDbFile());

                // Повна lifecycle-ініціалізація (DataSource + migrations + optional index)
                // не повинна блокувати JavaFX thread.
                Collection activated = switchCollectionUseCase.execute(collection, request.isCreateIndex());

                UiExecutor.runOnUiThread(() -> {
                    try {
                        CollectionDto dto = CollectionDto.builder()
                                .id(activated.getId())
                                .name(activated.getName())
                                .active(true)
                                .allowRename(true)
                                .allowDelete(true)
                                .rootFolder(activated.getRootFolder() != null ? activated.getRootFolder().toString() : null)
                                .dbFile(activated.getDbFile())
                                .type(activated.getType())
                                .booksCount(-1L)
                                .build();

                        if (collectionList != null) {
                            collectionList.add(dto);
                            log.info("Колекцію додано до списку UI: {}", dto.getName());
                        }

                        appState.setCurrentLibraryCollection(activated);

                        statisticsService.refreshStatistics();
                        syncSeriesUseCase.execute();

                        eventPublisher.publishEvent(new NavigationRefreshEvent());

                        appState.getStatusBar().setStatusText("Колекцію '" + activated.getName() + "' створено");
                        dialogService.showInfo("Успішно", "Колекцію '" + activated.getName() + "' створено!");

                        finishButton.setDisable(false);
                        finishButton.setText("✅ Створити");

                        if (onComplete != null) {
                            onComplete.run();
                        }
                        closeDialog();

                    } catch (Exception e) {
                        log.error("Помилка оновлення UI після створення колекції", e);
                        dialogService.showError("Помилка", "Колекцію створено, але не вдалося оновити UI: " + e.getMessage());
                        finishButton.setDisable(false);
                        finishButton.setText("✅ Створити");
                    }
                });

            } catch (Exception e) {
                log.error("Помилка створення колекції", e);
                UiExecutor.runOnUiThread(() -> {
                    showError("Помилка створення: " + e.getMessage());
                    finishButton.setDisable(false);
                    finishButton.setText("✅ Створити");
                    appState.getStatusBar().setStatusText("Помилка створення колекції");
                });
            }
        }).start();
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    @FXML
    private void onSelectRootFolder() {
        File dir = fileChooserService.chooseDirectory(stage, "Виберіть кореневу папку");
        if (dir != null) {
            model.setRootFolder(dir.toPath());
        }
    }

    @FXML
    private void onSelectDbPath() {
        File file = fileChooserService.chooseFileToSave(stage,
                "Виберіть місце для бази даних",
                model.getName() + ".db");
        if (file != null) {
            model.setDbFile(file.toPath());
        }
    }

    @FXML
    private void onSelectSourcePath() {
        File file = fileChooserService.chooseFile(stage,
                "Виберіть файл джерела",
                List.of(
                        new javafx.stage.FileChooser.ExtensionFilter("Всі підтримувані", "*.inpx", "*.inp", "*.fb2", "*.fbd", "*.epub", "*.txt", "*.zip", "*.fb2zip", "*.7z", "*.rar", "*.cbz"),
                        new javafx.stage.FileChooser.ExtensionFilter("INPX/INP", "*.inpx", "*.inp"),
                        new javafx.stage.FileChooser.ExtensionFilter("Книги", "*.fb2", "*.fbd", "*.epub", "*.txt"),
                        new javafx.stage.FileChooser.ExtensionFilter("Архіви", "*.zip", "*.fb2zip", "*.7z", "*.rar", "*.cbz")
                ));
        if (file != null) {
            model.setSourcePath(file.getAbsolutePath());
        }
    }

    private void updateStep(int step) {
        model.setCurrentStep(step);
        backButton.setDisable(step == 0);
        nextButton.setVisible(step < 2);
        finishButton.setVisible(step == 2);
        errorLabel.setText("");
        errorLabel2.setText("");

        if (step == 2) {
            updateConfirmation();
        }

        for (int i = 0; i < wizardContent.getChildren().size(); i++) {
            wizardContent.getChildren().get(i).setVisible(i == step);
        }
    }

    private void validate() {
        boolean valid = model.isStepValid(model.getCurrentStep());
        nextButton.setDisable(!valid && model.getCurrentStep() < 2);
        finishButton.setDisable(!model.isValid() && model.getCurrentStep() == 2);
        errorLabel.setText("");
    }

    private void showError(String message) {
        errorLabel.setText("❌ " + message);
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel2.setText("❌ " + message);
        errorLabel2.setStyle("-fx-text-fill: red;");
    }

    private CreateCollectionRequest buildRequest() {
        return CreateCollectionRequest.builder()
                .name(model.getName())
                .rootFolder(model.getRootFolder())
                .dbFile(model.getDbFile())
                .sourcePath(model.getSourcePath())
                .typeCode(model.getType().getCode())
                .importOnCreate(model.isImportOnCreate())
                .createIndex(model.isCreateIndex())
                .url(model.getUrl())
                .user(model.getUser())
                .password(model.getPassword())
                .build();
    }

    private void closeDialog() {
        if (stage != null) {
            stage.close();
        }
    }

    private void updateConfirmation() {
        confirmName.setText(model.getName() != null ? model.getName() : "");
        confirmType.setText(model.getType() != null ? model.getType().getDisplayName() : "");
        confirmRootFolder.setText(model.getRootFolder() != null ? model.getRootFolder().toString() : "");
        confirmDbFile.setText(model.getDbFile() != null ? model.getDbFile().toString() : "");
        confirmSourcePath.setText(model.getSourcePath() != null ? model.getSourcePath() : "");
        confirmImportOnCreate.setText(model.isImportOnCreate() ? "Так" : "Ні");
        confirmCreateIndex.setText(model.isCreateIndex() ? "Так" : "Ні");
    }
}