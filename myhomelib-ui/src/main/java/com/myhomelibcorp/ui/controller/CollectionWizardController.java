package com.myhomelibcorp.ui.controller;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.ui.collection.CollectionUiCommitGuard;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.application.service.PostImportMaintenanceService;
import com.myhomelibcorp.application.port.out.validation.CollectionValidatorPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.collection.CreateCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.LoadCollectionsUseCase;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import com.myhomelibcorp.ui.imports.ImportFileChooserFilters;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
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

    private final LocalizationService localizationService;
    private final CreateCollectionUseCase createCollectionUseCase;
    private final LoadCollectionsUseCase loadCollectionsUseCase;
    private final CollectionValidatorPort collectionValidator;
    private final DialogService dialogService;
    private final FileChooserService fileChooserService;
    private final SwitchCollectionUseCase switchCollectionUseCase;
    private final PostImportMaintenanceService collectionMaintenanceService;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationState appState;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;
    private final ApplicationSettingsPort applicationSettings;
    private final CollectionUiCommitGuard collectionUiCommitGuard;

    private final CollectionWizardViewModel model = new CollectionWizardViewModel();

    @FXML private TextField nameField;
    @FXML private TextField rootFolderField;
    @FXML private TextField dbPathField;
    @FXML private ComboBox<CollectionType> typeComboBox;
    @FXML private TextField sourcePathField;
    @FXML private TextField urlField;
    @FXML private Label catalogUpdateUrlLabel;
    @FXML private TextField catalogUpdateUrlField;
    @FXML private TextField userField;
    @FXML private PasswordField passwordField;
    @FXML private Label connectionScriptLabel;
    @FXML private TextArea connectionScriptArea;
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
    @FXML private Label confirmCatalogUpdateUrl;
    @FXML private Label confirmImportOnCreate;
    @FXML private Label confirmCreateIndex;
    @FXML private Label errorLabel2;
    @FXML private Label step1Indicator;
    @FXML private Label step2Indicator;
    @FXML private Label step3Indicator;

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
                return type == null ? "" : localizationService.text(type.getLocalizationKey());
            }
            @Override
            public CollectionType fromString(String string) {
                if (string == null || string.isBlank()) return CollectionType.FB2_LOCAL;
                for (CollectionType type : CollectionType.values()) {
                    if (localizationService.text(type.getLocalizationKey()).equalsIgnoreCase(string.trim()) || type.name().equalsIgnoreCase(string.trim())) return type;
                }
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
        catalogUpdateUrlField.textProperty().bindBidirectional(model.catalogUpdateUrlProperty());
        userField.textProperty().bindBidirectional(model.userProperty());
        passwordField.textProperty().bindBidirectional(model.passwordProperty());
        connectionScriptArea.textProperty().bindBidirectional(model.connectionScriptProperty());
        typeComboBox.valueProperty().bindBidirectional(model.typeProperty());
        typeComboBox.valueProperty().addListener((obs, oldType, newType) -> updateOnlineFieldsVisibility(newType));
        updateOnlineFieldsVisibility(typeComboBox.getValue());
        importOnCreateCheck.selectedProperty().bindBidirectional(model.importOnCreateProperty());
        createIndexCheck.selectedProperty().bindBidirectional(model.createIndexProperty());

        nameField.textProperty().addListener((obs, old, val) -> validate());
        rootFolderField.textProperty().addListener((obs, old, val) -> validate());
        dbPathField.textProperty().addListener((obs, old, val) -> validate());
        sourcePathField.textProperty().addListener((obs, old, val) -> validate());
        urlField.textProperty().addListener((obs, old, val) -> validate());
        catalogUpdateUrlField.textProperty().addListener((obs, old, val) -> validate());

        validate();
        updateStep(0);
    }

    public void setStage(Stage stage) {
        this.stage = stage;
        if (stage != null) {
            stage.setMinWidth(Math.max(720, stage.getMinWidth()));
            stage.setMinHeight(Math.max(560, stage.getMinHeight()));
        }
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
                showError(UiText.text("ui.java.collection_wizard_controller.05405f50fd"));
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
            showError(UiText.text("ui.java.collection_wizard_controller.4956926f1c"));
            return;
        }

        CreateCollectionRequest request = buildRequest();
        String catalogUpdateUrl = model.getCatalogUpdateUrl() == null ? "" : model.getCatalogUpdateUrl().trim();
        List<String> errors = collectionValidator.validate(request);
        if (!errors.isEmpty()) {
            showError(String.join("\n", errors));
            return;
        }

        finishButton.setDisable(true);
        finishButton.setText(UiText.text("ui.java.collection_wizard_controller.d0bbf3b822"));
        appState.getStatusBar().setStatusText(UiText.text("ui.java.collection_wizard_controller.b6abfa8e3a"));
        String operationId = operationCenter.start(
                UiText.text("ui.java.collection_wizard_controller.9dce38545e") + request.getName(), "", OperationStage.CREATING_COLLECTION, false);

        executor.submit(() -> {
                    log.info("Початок створення колекції: {}", request.getName());
                    Collection collection = createCollectionUseCase.execute(request);
                    log.info("Колекцію створено: id={}, name={}, dbFile={}",
                            collection.getId(), collection.getName(), collection.getDbFile());

                    // DataSource/migrations/index/statistics/series are all non-UI work.
                    Collection activated = switchCollectionUseCase.execute(collection, request.isCreateIndex());
                    operationCenter.accept(UiText.text("ui.java.collection_wizard_controller.9dce38545e") + request.getName(), activated.getId(),
                            OperationProgress.stage(operationId, OperationStage.REFRESHING_STATISTICS, false));
                    collectionMaintenanceService.refreshCollectionDerivedState(activated.getId());
                    return activated;
                })
                .whenComplete((activated, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        operationCenter.fail(operationId, cause);
                        log.error("Помилка створення колекції", cause);
                        showError(UiText.text("ui.java.collection_wizard_controller.318933ff83") + UiExceptionSupport.message(cause));
                        finishButton.setDisable(false);
                        finishButton.setText(UiText.text("ui.fxml.collection_wizard.create"));
                        appState.getStatusBar().setStatusText(UiText.text("ui.java.collection_wizard_controller.ab1972de78"));
                        return;
                    }

                    try {
                        boolean committed = collectionUiCommitGuard.runIfStillActive(activated.getId(), () -> {
                            appState.setCurrentLibraryCollection(activated);
                            if (collectionList != null) {
                                collectionList.setAll(loadCollectionsUseCase.execute());
                                log.info("Список колекцій перечитано після активації: {}", activated.getName());
                            }
                            eventPublisher.publishEvent(new NavigationRefreshEvent());
                            appState.getStatusBar().setStatusText(UiText.text("ui.java.collection_wizard_controller.581a96f07f")
                                    + activated.getName() + UiText.text("ui.java.collection_wizard_controller.eccde05e49"));
                        });
                        if (!catalogUpdateUrl.isBlank() && activated.getId() != null && !activated.getId().isBlank()) {
                            applicationSettings.put("collection." + activated.getId() + ".inpxUrl", catalogUpdateUrl);
                        }
                        if (!committed) {
                            log.debug("Created collection {} was superseded before wizard UI commit", activated.getId());
                        }
                        operationCenter.complete(operationId, UiText.text("ui.java.collection_wizard_controller.1e198f406d") + activated.getName());
                        dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.collection_wizard_controller.581a96f07f") + activated.getName() + UiText.text("ui.java.collection_wizard_controller.293e1ea2f2"));
                        finishButton.setDisable(false);
                        finishButton.setText(UiText.text("ui.fxml.collection_wizard.create"));
                        if (onComplete != null) onComplete.run();
                        closeDialog();
                    } catch (RuntimeException uiError) {
                        log.error("Помилка оновлення UI після створення колекції", uiError);
                        dialogService.showError(UiText.text("common.error"),
                                UiText.text("ui.java.collection_wizard_controller.f4e5ab9b5f") + UiExceptionSupport.message(uiError));
                        finishButton.setDisable(false);
                        finishButton.setText(UiText.text("ui.fxml.collection_wizard.create"));
                    }
                }));
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    @FXML
    private void onSelectRootFolder() {
        File dir = fileChooserService.chooseDirectory(stage, UiText.text("ui.java.collection_wizard_controller.a113f08b84"));
        if (dir != null) {
            model.setRootFolder(dir.toPath());
        }
    }

    @FXML
    private void onSelectDbPath() {
        File file = fileChooserService.chooseFileToSave(stage,
                UiText.text("ui.java.collection_wizard_controller.81637f1df0"),
                model.getName() + ".db");
        if (file != null) {
            model.setDbFile(file.toPath());
        }
    }

    @FXML
    private void onSelectSourcePath() {
        File file = fileChooserService.chooseFile(stage,
                UiText.text("ui.java.collection_wizard_controller.e3075ac8a6"),
                ImportFileChooserFilters.standardGroups(localizationService));
        if (file != null) {
            model.setSourcePath(file.getAbsolutePath());
        }
    }

    private void updateStep(int step) {
        model.setCurrentStep(step);
        backButton.setDisable(step == 0);
        nextButton.setVisible(step < 2);
        nextButton.setManaged(step < 2);
        finishButton.setVisible(step == 2);
        finishButton.setManaged(step == 2);
        errorLabel.setText("");
        errorLabel2.setText("");

        updateStepIndicators(step);
        if (step == 2) {
            updateConfirmation();
        }

        for (int i = 0; i < wizardContent.getChildren().size(); i++) {
            boolean active = i == step;
            wizardContent.getChildren().get(i).setVisible(active);
            wizardContent.getChildren().get(i).setManaged(active);
        }
    }

    private void updateStepIndicators(int activeStep) {
        Label[] indicators = {step1Indicator, step2Indicator, step3Indicator};
        for (int i = 0; i < indicators.length; i++) {
            Label indicator = indicators[i];
            if (indicator == null) continue;
            indicator.getStyleClass().remove("wizard-step-active");
            if (i == activeStep) {
                indicator.getStyleClass().add("wizard-step-active");
            }
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
                .connectionScript(model.getConnectionScript())
                .build();
    }


    private void updateOnlineFieldsVisibility(CollectionType type) {
        boolean online = type != null && type.requiresUrl();
        if (catalogUpdateUrlLabel != null) {
            catalogUpdateUrlLabel.setVisible(online);
            catalogUpdateUrlLabel.setManaged(online);
        }
        if (catalogUpdateUrlField != null) {
            catalogUpdateUrlField.setVisible(online);
            catalogUpdateUrlField.setManaged(online);
        }
        if (connectionScriptLabel != null) {
            connectionScriptLabel.setVisible(online);
            connectionScriptLabel.setManaged(online);
        }
        if (connectionScriptArea != null) {
            connectionScriptArea.setVisible(online);
            connectionScriptArea.setManaged(online);
        }
    }

    private void closeDialog() {
        if (stage != null) {
            stage.close();
        }
    }

    private void updateConfirmation() {
        confirmName.setText(model.getName() != null ? model.getName() : "");
        confirmType.setText(model.getType() != null ? localizationService.text(model.getType().getLocalizationKey()) : "");
        confirmRootFolder.setText(model.getRootFolder() != null ? model.getRootFolder().toString() : "");
        confirmDbFile.setText(model.getDbFile() != null ? model.getDbFile().toString() : "");
        confirmSourcePath.setText(model.getSourcePath() != null ? model.getSourcePath() : "");
        if (confirmCatalogUpdateUrl != null) {
            confirmCatalogUpdateUrl.setText(model.getCatalogUpdateUrl() != null ? model.getCatalogUpdateUrl() : "");
        }
        confirmImportOnCreate.setText(model.isImportOnCreate() ? UiText.text("ui.smart_collection.operator.is_true") : UiText.text("ui.smart_collection.operator.is_false"));
        confirmCreateIndex.setText(model.isCreateIndex() ? UiText.text("ui.smart_collection.operator.is_true") : UiText.text("ui.smart_collection.operator.is_false"));
    }
}
