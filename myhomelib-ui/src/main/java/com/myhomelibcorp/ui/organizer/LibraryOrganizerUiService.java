package com.myhomelibcorp.ui.organizer;

import com.myhomelibcorp.application.settings.ApplicationSettingsQueryService;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.organizer.analysis.AnalyzeLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.analysis.AuthorFolderPreviewPort;
import com.myhomelibcorp.organizer.analysis.OrganizationAnalysis;
import com.myhomelibcorp.organizer.analysis.OrganizationAnalysisProgress;
import com.myhomelibcorp.organizer.analysis.OrganizerAnalysisItem;
import com.myhomelibcorp.organizer.apply.ApplyLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.apply.GetLibraryOrganizationStatusUseCase;
import com.myhomelibcorp.organizer.apply.LibraryOrganizationStatus;
import com.myhomelibcorp.organizer.apply.ApplyResult;
import com.myhomelibcorp.organizer.apply.OrganizationApplyProgress;
import com.myhomelibcorp.organizer.apply.UndoLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.duplicate.DuplicateKind;
import com.myhomelibcorp.organizer.plan.OrganizationAction;
import com.myhomelibcorp.organizer.plan.OrganizationPlan;
import com.myhomelibcorp.organizer.plan.OrganizationPlanItem;
import com.myhomelibcorp.organizer.plan.PlanLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.policy.OriginalRetentionPolicy;
import com.myhomelibcorp.organizer.policy.OrganizerDuplicatePolicy;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import com.myhomelibcorp.organizer.policy.OrganizerProfiles;
import com.myhomelibcorp.organizer.policy.PdfPolicy;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Six-step Organizer wizard: source -> rules -> analyze -> preview -> apply -> result. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryOrganizerUiService {
    private final ApplicationState appState;
    private final AuthorFolderPreviewPort authorFolderPreview;
    private final AnalyzeLibraryOrganizationUseCase analyzer;
    private final PlanLibraryOrganizationUseCase planner;
    private final ApplyLibraryOrganizationUseCase applyUseCase;
    private final UndoLibraryOrganizationUseCase undoUseCase;
    private final GetLibraryOrganizationStatusUseCase statusUseCase;
    private final ApplicationSettingsQueryService settings;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;
    private final FileChooserService fileChooserService;
    private final ApplicationEventPublisher events;
    private final OperationCenterService operationCenter;
    // Multiple organizer windows share at most one expensive recovery scan.
    private final Object recoveryScanMonitor = new Object();
    private CompletableFuture<List<LibraryOrganizationStatus>> sharedRecoveryScan;
    private CompletableFuture<List<LibraryOrganizationStatus>> sharedDetailedRecoveryScan;

    private CompletableFuture<List<LibraryOrganizationStatus>> scanRecoveryOffFx() {
        synchronized (recoveryScanMonitor) {
            if (sharedRecoveryScan != null && !sharedRecoveryScan.isDone()) return sharedRecoveryScan;
            sharedRecoveryScan = background.submitCollectionScopedOperation(
                    com.myhomelibcorp.application.operation.LibraryOperationType.QUERY, statusUseCase::incompleteOverview);
            return sharedRecoveryScan;
        }
    }

    /** Full content-integrity checks only when the user chooses to inspect recovery. */
    private CompletableFuture<List<LibraryOrganizationStatus>> scanRecoveryDetailsOffFx() {
        synchronized (recoveryScanMonitor) {
            if (sharedDetailedRecoveryScan != null && !sharedDetailedRecoveryScan.isDone()) return sharedDetailedRecoveryScan;
            sharedDetailedRecoveryScan = background.submitCollectionScopedOperation(
                    com.myhomelibcorp.application.operation.LibraryOperationType.QUERY, statusUseCase::incomplete);
            return sharedDetailedRecoveryScan;
        }
    }

    public void show(Window owner) {
        new Wizard(owner).show();
    }

    private record AnalysisProgressUpdate(String operationId, OrganizationAnalysisProgress progress) { }

    private final class Wizard {
        private final Stage stage = new Stage();
        private final StackPane pageHost = new StackPane();
        private final Label stepLabel = new Label();
        private final Label pageTitle = new Label();
        private final Button backButton = new Button(UiText.text("ui.organizer.back"));
        private final Button nextButton = new Button(UiText.text("ui.organizer.next"));
        private final Button closeButton = new Button(UiText.text("common.close"));
        private final AtomicBoolean cancelRequested = new AtomicBoolean();

        private final RadioButton currentCollectionSource = new RadioButton(UiText.text("ui.organizer.source.current_collection"));
        private final RadioButton externalFolderSource = new RadioButton(UiText.text("ui.organizer.source.other_folder"));
        private final TextField sourceField = new TextField();
        private final TextField destinationField = new TextField();
        private final Label recoveryBanner = new Label();
        private final Button recoveryButton = new Button(UiText.text("ui.organizer.recovery.review"));
        private final Button authorPreviewButton = new Button(UiText.text("ui.organizer.author_preview.button"));
        private final Button authorBatchButton = new Button(UiText.text("ui.organizer.author_batch.button"));
        private final Button authorSelectionButton = new Button(UiText.text("ui.organizer.author_selection.button"));
        private final OrganizerAuthorFolderActions authorFolderActions = new OrganizerAuthorFolderActions(
                appState, authorFolderPreview, background, dialogs, fileChooserService,
                stage, currentCollectionSource, authorPreviewButton, authorBatchButton, authorSelectionButton);
        private List<LibraryOrganizationStatus> incompleteOperations = List.of();
        private long recoveryRequestVersion;
        private boolean recoveryLoading;

        private final ComboBox<ProfileChoice> profileBox = new ComboBox<>();
        private final TextField directoryTemplate = new TextField();
        private final TextField filenameTemplate = new TextField();
        private final ComboBox<LabeledValue<String>> canonicalFormat = new ComboBox<>();
        private final ComboBox<LabeledValue<PdfPolicy>> pdfPolicy = new ComboBox<>();
        private final ComboBox<LabeledValue<OriginalRetentionPolicy>> originalRetention = new ComboBox<>();
        private final ComboBox<LabeledValue<OrganizerDuplicatePolicy>> duplicatePolicy = new ComboBox<>();
        private final ComboBox<LabeledValue<EpubOptimizationProfile>> epubOptimization = new ComboBox<>();
        private final CheckBox normalizeMetadata = new CheckBox(UiText.text("ui.organizer.rules.normalize_metadata"));
        private final CheckBox validateConverted = new CheckBox(UiText.text("ui.organizer.rules.validate_converted"));
        private final Spinner<Integer> analysisThreads = new Spinner<>();
        private final Spinner<Integer> conversionThreads = new Spinner<>();

        private final ProgressBar analysisProgress = new ProgressBar();
        private final Label analysisStatus = new Label();
        private final Label analysisCounters = new Label();
        private final Label analysisCurrentFile = new Label();
        private final TextArea analysisWarnings = new TextArea();

        private final TableView<PreviewRow> previewTable = new TableView<>();
        private final javafx.collections.ObservableList<PreviewRow> previewRows = FXCollections.observableArrayList();
        private final FilteredList<PreviewRow> filteredRows = new FilteredList<>(previewRows, row -> true);
        private final ComboBox<FilterChoice> previewFilter = new ComboBox<>();
        private final Label previewSummary = new Label();

        private final ProgressBar applyProgress = new ProgressBar();
        private final Label applyStatus = new Label();
        private final Label applyCounters = new Label();
        private final Label applyCurrentFile = new Label();

        private final Label resultSummary = new Label();
        private final TextArea resultDetails = new TextArea();
        private final Button openDestinationButton = new Button(UiText.text("ui.organizer.result.open_destination"));
        private final Button exportReportButton = new Button(UiText.text("ui.organizer.result.export_report"));
        private final Button undoButton = new Button(UiText.text("ui.organizer.result.undo"));

        private final List<Node> pages = new ArrayList<>();
        private int pageIndex;
        private volatile boolean busy;
        private String analysisOperationId;
        private String analysisOperationTitle;
        private String analysisCollectionId;
        private OrganizationAnalysis analysis;
        private OrganizationPlan plan;
        private OrganizationPlan appliedPlan;
        private ApplyResult applyResult;
        private final LatestUiProgress<AnalysisProgressUpdate> analysisUiProgress =
                new LatestUiProgress<>(UiExecutor::runOnUiThread, update -> {
                    if (busy && Objects.equals(analysisOperationId, update.operationId())) {
                        updateAnalysisProgress(update.progress());
                    }
                });
        private final LatestUiProgress<OrganizationApplyProgress> applyUiProgress =
                new LatestUiProgress<>(UiExecutor::runOnUiThread, value -> {
                    if (busy) updateApplyProgress(value);
                });



        Wizard(Window owner) {
            stage.setTitle(UiText.text("ui.organizer.title"));
            stage.initModality(Modality.NONE);
            if (owner != null) stage.initOwner(owner);
            configureSourcePage();
            configureRulesPage();
            configureAnalysisPage();
            configurePreviewPage();
            configureApplyPage();
            configureResultPage();
            configureStage();
            loadDefaultSource();
            selectProfile(OrganizerProfiles.SAFE);
            showPage(0);
        }

        void show() {
            stage.show();
        }

        private void configureStage() {
            pageTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
            stepLabel.setStyle("-fx-opacity: 0.75;");
            VBox header = new VBox(3, stepLabel, pageTitle);
            header.setPadding(new Insets(12, 14, 8, 14));

            backButton.setOnAction(event -> goBack());
            nextButton.setOnAction(event -> goNext());
            closeButton.setOnAction(event -> {
                if (busy) {
                    cancelRequested.set(true);
                    closeButton.setDisable(true);
                    applyStatus.setText(UiText.text("ui.organizer.cancelling"));
                    analysisStatus.setText(UiText.text("ui.organizer.cancelling"));
                } else {
                    stage.close();
                }
            });
            HBox footer = new HBox(8, backButton, nextButton, closeButton);
            footer.setAlignment(Pos.CENTER_RIGHT);
            footer.setPadding(new Insets(8, 14, 12, 14));

            BorderPane root = new BorderPane();
            root.setTop(header);
            root.setCenter(pageHost);
            root.setBottom(footer);
            stage.setScene(new Scene(root, 1240, 760));
            stage.setMinWidth(920);
            stage.setMinHeight(620);
            stage.setOnHidden(event -> recoveryRequestVersion++);
            stage.setOnCloseRequest(event -> {
                if (busy) {
                    cancelRequested.set(true);
                    event.consume();
                }
            });
        }

        private void configureSourcePage() {
            ToggleGroup group = new ToggleGroup();
            currentCollectionSource.setToggleGroup(group);
            externalFolderSource.setToggleGroup(group);
            currentCollectionSource.setOnAction(event -> applyCurrentCollectionSource());
            externalFolderSource.setOnAction(event -> {
                if (sourceField.getText().isBlank()) chooseDirectory(sourceField, UiText.text("ui.organizer.source.choose_source"));
                if (destinationField.getText().isBlank() && !sourceField.getText().isBlank()) {
                    destinationField.setText(Path.of(sourceField.getText()).resolve("MyHomeLib-Organized").toString());
                }
            });

            Button chooseSource = new Button(UiText.text("ui.organizer.choose"));
            chooseSource.setOnAction(event -> chooseDirectory(sourceField, UiText.text("ui.organizer.source.choose_source")));
            Button chooseDestination = new Button(UiText.text("ui.organizer.choose"));
            chooseDestination.setOnAction(event -> chooseDirectory(destinationField, UiText.text("ui.organizer.source.choose_destination")));

            recoveryBanner.setWrapText(true);
            recoveryButton.setOnAction(event -> reviewRecovery());
            HBox recovery = new HBox(8, recoveryBanner, recoveryButton);
            recovery.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(recoveryBanner, Priority.ALWAYS);

            VBox box = pageBox();
            Label intro = wrap(UiText.text("ui.organizer.source.info"));
            authorPreviewButton.setOnAction(event -> authorFolderActions.previewAuthorFolder());
            authorBatchButton.setOnAction(event -> authorFolderActions.auditAuthorFolders());
            authorSelectionButton.setOnAction(event -> authorFolderActions.prepareOfflineAuthorRepair());
            box.getChildren().addAll(intro, recovery, currentCollectionSource, externalFolderSource, new Separator(),
                    authorBatchButton, authorSelectionButton,
                    row(UiText.text("ui.organizer.source.root"), fieldWithButton(sourceField, chooseSource)),
                    row(UiText.text("ui.organizer.source.destination"), fieldWithButton(destinationField, chooseDestination)),
                    authorPreviewButton, wrap(UiText.text("ui.organizer.source.safety")));
            pages.add(scroll(box));
            refreshRecoveryState();
        }

        /** Optional, read-only inspection of a single folder: all SQL and disk access is off JavaFX. */
        private void configureRulesPage() {
            for (OrganizerProfile profile : OrganizerProfiles.builtIns()) {
                profileBox.getItems().add(new ProfileChoice(profile, profileLabel(profile.id())));
            }
            profileBox.valueProperty().addListener((obs, oldValue, newValue) -> {
                if (newValue != null) applyProfile(newValue.profile());
            });

            canonicalFormat.getItems().setAll(List.of(
                    new LabeledValue<>("epub", UiText.text("ui.organizer.format.epub3")),
                    new LabeledValue<>("original", UiText.text("ui.organizer.format.original"))));
            pdfPolicy.getItems().setAll(List.of(
                    new LabeledValue<>(PdfPolicy.KEEP_PDF, UiText.text("ui.organizer.pdf.keep")),
                    new LabeledValue<>(PdfPolicy.CONVERT_WHEN_EXPLICITLY_SUPPORTED, UiText.text("ui.organizer.pdf.convert_explicit"))));
            originalRetention.getItems().setAll(List.of(
                    new LabeledValue<>(OriginalRetentionPolicy.KEEP_ORIGINAL, UiText.text("ui.organizer.original.keep")),
                    new LabeledValue<>(OriginalRetentionPolicy.MOVE_ORIGINAL_TO_ARCHIVE, UiText.text("ui.organizer.original.archive")),
                    new LabeledValue<>(OriginalRetentionPolicy.DELETE_ORIGINAL_AFTER_VERIFIED_CONVERSION, UiText.text("ui.organizer.original.quarantine"))));
            duplicatePolicy.getItems().setAll(List.of(
                    new LabeledValue<>(OrganizerDuplicatePolicy.KEEP_ALL, UiText.text("ui.organizer.duplicates.keep")),
                    new LabeledValue<>(OrganizerDuplicatePolicy.REVIEW, UiText.text("ui.organizer.duplicates.review")),
                    new LabeledValue<>(OrganizerDuplicatePolicy.DELETE_EXACT_ONLY, UiText.text("ui.organizer.duplicates.exact"))));
            epubOptimization.getItems().setAll(List.of(
                    new LabeledValue<>(EpubOptimizationProfile.NONE, UiText.text("ui.settings.converters.optimizer.none")),
                    new LabeledValue<>(EpubOptimizationProfile.COMPATIBLE_EPUB33, UiText.text("ui.settings.converters.optimizer.compatible")),
                    new LabeledValue<>(EpubOptimizationProfile.BALANCED_EPUB33, UiText.text("ui.settings.converters.optimizer.balanced")),
                    new LabeledValue<>(EpubOptimizationProfile.MAXIMUM_EPUB33, UiText.text("ui.settings.converters.optimizer.maximum")),
                    new LabeledValue<>(EpubOptimizationProfile.MODERN_EPUB34, UiText.text("ui.settings.converters.optimizer.modern"))));
            analysisThreads.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 16, Math.min(4, Runtime.getRuntime().availableProcessors())));
            conversionThreads.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 4, 1));

            VBox box = pageBox();
            box.getChildren().addAll(
                    row(UiText.text("ui.organizer.rules.profile"), profileBox),
                    new Separator(),
                    row(UiText.text("ui.organizer.rules.directory_template"), directoryTemplate),
                    row(UiText.text("ui.organizer.rules.filename_template"), filenameTemplate),
                    row(UiText.text("ui.organizer.rules.canonical_format"), canonicalFormat),
                    row(UiText.text("ui.organizer.rules.pdf"), pdfPolicy),
                    row(UiText.text("ui.organizer.rules.originals"), originalRetention),
                    row(UiText.text("ui.organizer.rules.duplicates"), duplicatePolicy),
                    row(UiText.text("ui.organizer.rules.epub_optimization"), epubOptimization),
                    normalizeMetadata,
                    validateConverted,
                    row(UiText.text("ui.organizer.rules.analysis_threads"), analysisThreads),
                    row(UiText.text("ui.organizer.rules.conversion_threads"), conversionThreads),
                    wrap(UiText.text("ui.organizer.rules.destructive_note")));
            pages.add(scroll(box));
        }

        private void configureAnalysisPage() {
            analysisProgress.setMaxWidth(Double.MAX_VALUE);
            analysisProgress.setProgress(-1);
            analysisStatus.setWrapText(true);
            analysisCurrentFile.setWrapText(true);
            analysisWarnings.setEditable(false);
            analysisWarnings.setWrapText(true);
            analysisWarnings.setPrefRowCount(9);
            VBox box = pageBox();
            box.getChildren().addAll(analysisStatus, analysisProgress, analysisCounters,
                    new Label(UiText.text("ui.organizer.analysis.current")), analysisCurrentFile,
                    new Label(UiText.text("ui.organizer.analysis.warnings")), analysisWarnings);
            VBox.setVgrow(analysisWarnings, Priority.ALWAYS);
            pages.add(box);
        }

        private void configurePreviewPage() {
            previewTable.setEditable(true);
            previewTable.setItems(filteredRows);
            previewTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

            TableColumn<PreviewRow, Boolean> included = new TableColumn<>(UiText.text("ui.organizer.preview.include"));
            included.setCellValueFactory(data -> data.getValue().includedProperty());
            included.setCellFactory(CheckBoxTableCell.forTableColumn(included));
            included.setPrefWidth(55);
            previewTable.getColumns().add(included);
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.status"), PreviewRow::status, 145));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.current_path"), PreviewRow::sourcePath, 300));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.author"), PreviewRow::author, 170));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.series"), PreviewRow::series, 150));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.series_no"), PreviewRow::seriesNumber, 70));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.title"), PreviewRow::title, 220));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.format_now"), PreviewRow::sourceFormat, 90));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.size_now"), PreviewRow::sourceSize, 100));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.action"), PreviewRow::action, 160));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.source_impact"), PreviewRow::sourceImpact, 145));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.reason"), PreviewRow::reason, 250));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.optimization_profile"), PreviewRow::optimizationProfile, 160));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.format_after"), PreviewRow::targetFormat, 90));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.optimization_result"), PreviewRow::optimizationResult, 110));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.savings"), PreviewRow::estimatedSavings, 90));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.compatibility"), PreviewRow::compatibilityWarning, 190));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.new_path"), PreviewRow::targetPath, 300));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.duplicate"), PreviewRow::duplicate, 150));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.confidence"), PreviewRow::confidence, 90));
            previewTable.getColumns().add(column(UiText.text("ui.organizer.preview.warnings"), PreviewRow::warnings, 260));
            previewTable.setPlaceholder(new Label(UiText.text("ui.organizer.preview.empty")));

            previewFilter.getItems().setAll(FilterChoice.values());
            previewFilter.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(FilterChoice value) { return value == null ? "" : filterLabel(value); }
                @Override public FilterChoice fromString(String string) { return FilterChoice.ALL; }
            });
            previewFilter.setValue(FilterChoice.ALL);
            previewFilter.valueProperty().addListener((obs, oldValue, newValue) -> applyPreviewFilter(newValue));

            Button selectVisible = new Button(UiText.text("ui.organizer.preview.select_visible"));
            selectVisible.setOnAction(event -> filteredRows.forEach(row -> row.setIncluded(row.safeDefaultSelection())));
            Button clearVisible = new Button(UiText.text("ui.organizer.preview.clear_visible"));
            clearVisible.setOnAction(event -> filteredRows.forEach(row -> row.setIncluded(false)));
            previewRows.addListener((javafx.collections.ListChangeListener<PreviewRow>) change -> updatePreviewSummary());

            HBox controls = new HBox(8, new Label(UiText.text("ui.organizer.preview.filter")), previewFilter, selectVisible, clearVisible);
            controls.setAlignment(Pos.CENTER_LEFT);
            VBox box = pageBox();
            box.getChildren().addAll(controls, previewSummary, previewTable,
                    wrap(UiText.text("ui.organizer.preview.destructive_note")));
            VBox.setVgrow(previewTable, Priority.ALWAYS);
            pages.add(box);
        }

        private void configureApplyPage() {
            applyProgress.setMaxWidth(Double.MAX_VALUE);
            applyProgress.setProgress(0);
            applyStatus.setWrapText(true);
            applyCurrentFile.setWrapText(true);
            VBox box = pageBox();
            box.getChildren().addAll(applyStatus, applyProgress, applyCounters,
                    new Label(UiText.text("ui.organizer.apply.current")), applyCurrentFile,
                    wrap(UiText.text("ui.organizer.apply.cancel_note")));
            pages.add(box);
        }

        private void configureResultPage() {
            resultSummary.setWrapText(true);
            resultSummary.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
            resultDetails.setEditable(false);
            resultDetails.setWrapText(true);
            resultDetails.setPrefRowCount(14);
            openDestinationButton.setOnAction(event -> openDestination());
            exportReportButton.setOnAction(event -> exportReport());
            undoButton.setOnAction(event -> undoOperation());
            undoButton.setDisable(true);
            HBox actions = new HBox(8, openDestinationButton, exportReportButton, undoButton);
            VBox box = pageBox();
            box.getChildren().addAll(resultSummary, resultDetails, actions);
            VBox.setVgrow(resultDetails, Priority.ALWAYS);
            pages.add(box);
        }

        private void refreshRecoveryState() {
            // Fast overview avoids per-book file checks; full verification happens on explicit review.
            // Classifying a crash journal performs Files.exists/readAttributes and hashes on
            // potentially unavailable Windows volumes. NEVER call incomplete() on JavaFX.
            long generation = ++recoveryRequestVersion;
            recoveryLoading = true;
            incompleteOperations = List.of();
            recoveryBanner.setText(UiText.text("ui.fxml.book_workspace.loading"));
            recoveryButton.setDisable(true);
            recoveryButton.setVisible(false);
            recoveryButton.setManaged(false);
            long started = System.nanoTime();
            log.info("ORGANIZER_RECOVERY_OVERVIEW_START");
            scanRecoveryOffFx().whenComplete((result, failure) -> UiExecutor.runOnUiThread(() -> {
                        log.info("ORGANIZER_RECOVERY_OVERVIEW_FINISH durationMs={} count={} success={}",
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                                result == null ? 0 : result.size(), failure == null);
                        if (generation != recoveryRequestVersion) return;
                        recoveryLoading = false;
                        if (failure != null) {
                            incompleteOperations = List.of();
                            log.warn("ORGANIZER_RECOVERY_SCAN_FAILED", failure);
                            recoveryBanner.setText(UiText.text("ui.organizer.recovery.read_failed") + " "
                                    + UiExceptionSupport.message(failure));
                        } else {
                            incompleteOperations = result == null ? List.of() : result;
                            recoveryBanner.setText(incompleteOperations.isEmpty()
                                    ? UiText.text("ui.organizer.recovery.none")
                                    : UiText.format("ui.organizer.recovery.found", incompleteOperations.size()));
                        }
                        boolean present = !incompleteOperations.isEmpty();
                        recoveryButton.setVisible(present);
                        recoveryButton.setManaged(present);
                        recoveryButton.setDisable(!present);
                    }));
        }

        private void reviewRecovery() {
            if (recoveryLoading || incompleteOperations.isEmpty() || busy) return;
            recoveryLoading = true;
            recoveryButton.setDisable(true);
            recoveryBanner.setText(UiText.text("ui.fxml.book_workspace.loading"));
            long generation = ++recoveryRequestVersion;
            long started = System.nanoTime();
            log.info("ORGANIZER_RECOVERY_DETAILED_SCAN_START");
            scanRecoveryDetailsOffFx().whenComplete((statuses, failure) -> UiExecutor.runOnUiThread(() -> {
                log.info("ORGANIZER_RECOVERY_DETAILED_SCAN_FINISH durationMs={} count={} success={}",
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        statuses == null ? 0 : statuses.size(), failure == null);
                if (generation != recoveryRequestVersion) return;
                recoveryLoading = false;
                if (failure != null) {
                    log.warn("ORGANIZER_RECOVERY_DETAILED_SCAN_FAILED", failure);
                    recoveryBanner.setText(UiText.text("ui.organizer.recovery.read_failed") + " "
                            + UiExceptionSupport.message(failure));
                    recoveryButton.setDisable(false);
                    return;
                }
                incompleteOperations = statuses == null ? List.of() : statuses;
                boolean present = !incompleteOperations.isEmpty();
                recoveryBanner.setText(present
                        ? UiText.format("ui.organizer.recovery.found", incompleteOperations.size())
                        : UiText.text("ui.organizer.recovery.none"));
                recoveryButton.setVisible(present);
                recoveryButton.setManaged(present);
                recoveryButton.setDisable(!present);
                if (present) showRecoveryChoice(incompleteOperations);
            }));
        }

        private void showRecoveryChoice(List<LibraryOrganizationStatus> verifiedStatuses) {
            LibraryOrganizationStatus selected = dialogs.showChoiceDialog(verifiedStatuses, verifiedStatuses.getFirst(),
                    UiText.text("ui.organizer.recovery.title"), null, UiText.text("ui.organizer.recovery.choose"),
                    this::recoveryLabel).orElse(null);
            if (selected == null) return;
            if (selected.needsManualReview()) {
                dialogs.showWarning(UiText.text("ui.organizer.recovery.title"),
                        UiText.format("ui.organizer.recovery.manual", recoveryStateLabel(selected.operation().state())));
                return;
            }
            if (!selected.canRollback()) {
                dialogs.showInfo(UiText.text("ui.organizer.recovery.title"),
                        UiText.format("ui.organizer.recovery.no_committed", recoveryStateLabel(selected.operation().state())));
                return;
            }
            if (!dialogs.showConfirmation(UiText.text("ui.organizer.recovery.rollback_title"), null,
                    UiText.text("ui.organizer.recovery.rollback_confirm"))) return;
            busy = true; updateNavigation();
            background.submit(() -> undoUseCase.undo(selected.operation().operationId(), selected.operation().collectionId()))
                    .whenComplete((restored, error) -> UiExecutor.runOnUiThread(() -> {
                        busy = false;
                        if (error != null) dialogs.showError(UiText.text("ui.organizer.recovery.title"), UiExceptionSupport.message(error));
                        else {
                            dialogs.showInfo(UiText.text("ui.organizer.recovery.title"), UiText.format("ui.organizer.recovery.rollback_done", restored));
                            events.publishEvent(new NavigationRefreshEvent());
                        }
                        refreshRecoveryState(); updateNavigation();
                    }));
        }

        private String recoveryLabel(LibraryOrganizationStatus status) {
            return UiText.format("ui.organizer.recovery.item",
                    java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(status.operation().startedAt()),
                    recoveryStateLabel(status.operation().state()));
        }

        private String recoveryStateLabel(com.myhomelibcorp.organizer.journal.OrganizationOperationState state) {
            if (state == null) return UiText.text("ui.organizer.recovery.state.unknown");
            return UiText.text("ui.organizer.recovery.state." + state.name().toLowerCase(Locale.ROOT));
        }

        private void loadDefaultSource() {
            var collection = appState.getCurrentLibraryCollection();
            boolean usable = collection != null && collection.getRootFolder() != null;
            currentCollectionSource.setDisable(!usable);
            if (usable) {
                currentCollectionSource.setSelected(true);
                applyCurrentCollectionSource();
            } else {
                externalFolderSource.setSelected(true);
            }
        }

        private void applyCurrentCollectionSource() {
            var collection = appState.getCurrentLibraryCollection();
            if (collection == null || collection.getRootFolder() == null) return;
            Path root = collection.getRootFolder().toAbsolutePath().normalize();
            sourceField.setText(root.toString());
            destinationField.setText(root.toString());
        }

        private void selectProfile(String id) {
            for (ProfileChoice choice : profileBox.getItems()) {
                if (choice.profile().id().equals(id)) {
                    profileBox.setValue(choice);
                    return;
                }
            }
            if (!profileBox.getItems().isEmpty()) profileBox.setValue(profileBox.getItems().getFirst());
        }

        private void applyProfile(OrganizerProfile profile) {
            directoryTemplate.setText(profile.directoryTemplate());
            filenameTemplate.setText(profile.filenameTemplate());
            selectValue(canonicalFormat, profile.canonicalReflowableFormat());
            selectValue(pdfPolicy, profile.pdfPolicy());
            selectValue(originalRetention, profile.originalRetention());
            selectValue(duplicatePolicy, profile.duplicatePolicy());
            selectValue(epubOptimization, profile.epubOptimizationProfile());
            normalizeMetadata.setSelected(profile.normalizeMetadata());
            validateConverted.setSelected(profile.validateConvertedFiles());
            analysisThreads.getValueFactory().setValue(profile.maxParallelAnalysis());
            conversionThreads.getValueFactory().setValue(profile.maxParallelConversions());
        }

        private OrganizerProfile currentProfile() {
            ProfileChoice selected = profileBox.getValue();
            String id = selected == null ? OrganizerProfiles.SAFE : selected.profile().id();
            String name = selected == null ? profileLabel(id) : selected.label();
            return new OrganizerProfile(id, name,
                    directoryTemplate.getText(), filenameTemplate.getText(), value(canonicalFormat, "epub"),
                    value(pdfPolicy, PdfPolicy.KEEP_PDF),
                    value(originalRetention, OriginalRetentionPolicy.KEEP_ORIGINAL),
                    value(duplicatePolicy, OrganizerDuplicatePolicy.REVIEW),
                    normalizeMetadata.isSelected(), validateConverted.isSelected(), false,
                    analysisThreads.getValue(), conversionThreads.getValue(),
                    value(epubOptimization, EpubOptimizationProfile.BALANCED_EPUB33));
        }

        private void goBack() {
            if (busy || pageIndex <= 0) return;
            if (pageIndex == 5 && appliedPlan != null && !appliedPlan.actions().isEmpty()) return;
            showPage(pageIndex - 1);
        }

        private void goNext() {
            if (busy) return;
            switch (pageIndex) {
                case 0 -> {
                    if (validateSource()) showPage(1);
                }
                case 1 -> startAnalysis();
                case 2 -> {
                    if (plan != null) showPage(3);
                }
                case 3 -> startApply();
                case 4 -> {
                    if (applyResult != null) showPage(5);
                }
                case 5 -> stage.close();
                default -> { }
            }
        }

        private boolean validateSource() {
            try {
                Path source = Path.of(sourceField.getText()).toAbsolutePath().normalize();
                Path destination = Path.of(destinationField.getText()).toAbsolutePath().normalize();
                if (!Files.isDirectory(source)) {
                    dialogs.showWarning(UiText.text("ui.organizer.title"), UiText.text("ui.organizer.error.source_directory"));
                    return false;
                }
                if (Files.exists(destination) && !Files.isDirectory(destination)) {
                    dialogs.showWarning(UiText.text("ui.organizer.title"), UiText.text("ui.organizer.error.destination_directory"));
                    return false;
                }
                if (value(epubOptimization, EpubOptimizationProfile.BALANCED_EPUB33) == EpubOptimizationProfile.MODERN_EPUB34
                        && !"MODERN_EPUB34_EXPERIMENTAL".equalsIgnoreCase(settings.get("converter.epubReadingSystemProfile", "GENERIC_EPUB33_COMPAT"))) {
                    dialogs.showWarning(UiText.text("ui.organizer.title"), UiText.text("ui.settings.converters.validation.epub34_profile"));
                    return false;
                }
                return true;
            } catch (Exception error) {
                dialogs.showWarning(UiText.text("ui.organizer.title"), UiText.text("ui.organizer.error.invalid_path"));
                return false;
            }
        }

        private void startAnalysis() {
            if (!validateSource()) return;
            OrganizerProfile profile = currentProfile();
            Path source = Path.of(sourceField.getText()).toAbsolutePath().normalize();
            Path destination = Path.of(destinationField.getText()).toAbsolutePath().normalize();
            String expectedCollectionId = UiAsyncRequestGuard.currentCollectionId(appState);
            analysis = null;
            plan = null;
            previewRows.clear();
            cancelRequested.set(false);
            busy = true;
            analysisProgress.setProgress(-1);
            analysisStatus.setText(UiText.text("ui.organizer.analysis.scanning"));
            analysisCounters.setText("");
            analysisCurrentFile.setText("");
            analysisWarnings.clear();
            showPage(2);
            updateNavigation();

            String operationTitle = UiText.text("ui.organizer.title") + " — " + UiText.text("ui.organizer.page.analysis");
            String operationId = operationCenter.start(operationTitle, expectedCollectionId, OperationKind.LIBRARY_ORGANIZATION,
                    OperationStage.VALIDATING, true, () -> cancelRequested.set(true));
            analysisUiProgress.clear();
            analysisOperationId = operationId;
            analysisOperationTitle = operationTitle;
            analysisCollectionId = expectedCollectionId;
            log.info("ORGANIZER_ANALYSIS_START source='{}' destination='{}' collection={}",
                    source, destination, expectedCollectionId);

            background.submit(() -> {
                        OrganizationAnalysis analyzed = analyzer.analyze(source, destination, profile, cancelRequested::get,
                                progress -> analysisUiProgress.offer(new AnalysisProgressUpdate(operationId, progress)));
                        if (cancelRequested.get()) throw new CancellationException("Organizer analysis cancelled");
                        OrganizationPlan planned = planner.plan(analyzed, profile)
                                .withExpectedCollectionId(expectedCollectionId);
                        return new AnalysisAndPlan(analyzed, planned);
                    })
                    .whenComplete((value, error) -> UiExecutor.runOnUiThread(() -> {
                        analysisUiProgress.clear();
                        if (error != null) {
                            busy = false;
                            Throwable cause = UiExceptionSupport.unwrapAsync(error);
                            if (cause instanceof CancellationException) {
                                operationCenter.cancel(operationId, UiText.text("ui.organizer.analysis.cancelled"));
                                log.info("ORGANIZER_ANALYSIS_CANCEL source='{}' collection={}", source, expectedCollectionId);
                                analysisStatus.setText(UiText.text("ui.organizer.analysis.cancelled"));
                            } else {
                                operationCenter.fail(operationId, cause);
                                log.error("ORGANIZER_ANALYSIS_FAIL source='{}' collection={} error={}",
                                        source, expectedCollectionId, UiExceptionSupport.message(cause), cause);
                                analysisStatus.setText(UiText.text("ui.organizer.analysis.failed") + " " + UiExceptionSupport.message(cause));
                            }
                            analysisProgress.setProgress(0);
                            updateNavigation();
                            return;
                        }
                        analysis = value.analysis();
                        plan = value.plan();
                        analysisProgress.setProgress(0.98);
                        analysisStatus.setText(UiText.text("ui.organizer.analysis.preparing_preview"));
                        analysisCounters.setText(UiText.format("ui.organizer.analysis.completed_counts", analysis.items().size(), plan.actions().size()));
                        analysisWarnings.setText(String.join("\n", analysis.warnings()));
                        buildPreviewIncrementally(() -> {
                            busy = false;
                            analysisProgress.setProgress(1);
                            if (cancelRequested.get()) {
                                operationCenter.cancel(operationId, UiText.text("ui.organizer.analysis.cancelled"));
                                log.info("ORGANIZER_ANALYSIS_CANCEL source='{}' collection={}", source, expectedCollectionId);
                            } else {
                                operationCenter.complete(operationId, UiText.text("ui.organizer.analysis.completed"));
                                log.info("ORGANIZER_ANALYSIS_DONE source='{}' collection={} files={} books={} actions={}",
                                        source, expectedCollectionId, analysis == null ? 0 : analysis.items().size(),
                                        analysis == null ? 0 : analysis.items().size(), plan == null ? 0 : plan.actions().size());
                            }
                            analysisStatus.setText(cancelRequested.get()
                                    ? UiText.text("ui.organizer.analysis.cancelled")
                                    : UiText.text("ui.organizer.analysis.completed"));
                            updateNavigation();
                        });
                    }));
        }

        private void updateAnalysisProgress(OrganizationAnalysisProgress progress) {
            if (progress == null) return;
            if (progress.scanning()) {
                analysisStatus.setText(UiText.text("ui.organizer.analysis.scanning"));
                analysisCounters.setText(UiText.format("ui.organizer.analysis.discovered", progress.filesDiscovered()));
                analysisProgress.setProgress(-1);
            } else {
                analysisStatus.setText(UiText.text("ui.organizer.analysis.processing"));
                analysisCounters.setText(UiText.format("ui.organizer.analysis.progress_counts",
                        progress.filesProcessed(), progress.filesDiscovered(), progress.booksAnalyzed(), progress.failedFiles()));
                analysisProgress.setProgress(progress.filesDiscovered() <= 0 ? 0
                        : Math.min(1.0, (double) progress.filesProcessed() / progress.filesDiscovered()));
            }
            analysisCurrentFile.setText(progress.currentPath() == null ? "" : progress.currentPath().toString());
            if (analysisOperationId != null) {
                OperationProgress telemetry = OperationProgress.stage(analysisOperationId, OperationStage.VALIDATING, true)
                        .withProgress(progress.filesProcessed(), progress.filesDiscovered())
                        .withCurrentItem(progress.currentPath() == null ? "" : progress.currentPath().toString());
                operationCenter.accept(analysisOperationTitle, analysisCollectionId, OperationKind.LIBRARY_ORGANIZATION, telemetry);
            }
        }

        private void buildPreviewIncrementally(Runnable finished) {
            previewRows.clear();
            if (analysis == null || plan == null) {
                if (finished != null) finished.run();
                return;
            }
            var bySource = analysis.items().stream().collect(Collectors.toMap(
                    item -> new AnalysisSourceKey(item.sourcePath(), item.sourceEntry()),
                    Function.identity(), (left, right) -> left));
            List<OrganizationPlanItem> actions = plan.actions();
            final int batchSize = 500;
            final int[] offset = {0};
            Runnable pump = new Runnable() {
                @Override public void run() {
                    if (cancelRequested.get()) {
                        previewRows.clear();
                        plan = null;
                        if (finished != null) finished.run();
                        return;
                    }
                    int end = Math.min(actions.size(), offset[0] + batchSize);
                    List<PreviewRow> batch = new ArrayList<>(Math.max(0, end - offset[0]));
                    for (int i = offset[0]; i < end; i++) {
                        OrganizationPlanItem item = actions.get(i);
                        OrganizerAnalysisItem analyzed = bySource.get(new AnalysisSourceKey(item.sourcePath(), item.sourceEntry()));
                        PreviewRow row = new PreviewRow(item, analyzed);
                        row.includedProperty().addListener((obs, oldValue, newValue) -> updatePreviewSummary());
                        batch.add(row);
                    }
                    if (!batch.isEmpty()) previewRows.addAll(batch);
                    offset[0] = end;
                    if (offset[0] < actions.size()) {
                        javafx.application.Platform.runLater(this);
                    } else {
                        applyPreviewFilter(previewFilter.getValue());
                        updatePreviewSummary();
                        if (finished != null) finished.run();
                    }
                }
            };
            javafx.application.Platform.runLater(pump);
        }

        private void applyPreviewFilter(FilterChoice filter) {
            FilterChoice effective = filter == null ? FilterChoice.ALL : filter;
            filteredRows.setPredicate(row -> switch (effective) {
                case ALL -> true;
                case ERRORS -> row.planItem().action() == OrganizationAction.ERROR || row.planItem().blocking();
                case CONFLICTS -> row.planItem().action() == OrganizationAction.CONFLICT;
                case CONVERSIONS -> row.planItem().conversionRequired() || row.planItem().action() == OrganizationAction.OPTIMIZE || row.planItem().action() == OrganizationAction.OPTIMIZE_AND_MOVE;
                case DUPLICATES -> row.planItem().duplicate().kind() != DuplicateKind.NONE;
                case LOW_CONFIDENCE -> row.planItem().confidence() < 70;
                case PDF -> "pdf".equalsIgnoreCase(row.planItem().sourceFormat());
                case UNKNOWN_AUTHOR -> row.analysisItem() == null || row.analysisItem().identity().firstAuthorDisplayName().isBlank();
                case UNKNOWN_SERIES -> row.analysisItem() == null || row.analysisItem().identity().series().isBlank();
            });
        }

        private void updatePreviewSummary() {
            long selected = previewRows.stream().filter(PreviewRow::included).count();
            long destructive = previewRows.stream().filter(PreviewRow::included)
                    .filter(row -> isDestructive(row.planItem())).count();
            previewSummary.setText(UiText.format("ui.organizer.preview.summary", previewRows.size(), selected, destructive));
        }

        private boolean isDestructive(OrganizationPlanItem item) {
            if (item == null) return false;
            return switch (item.action()) {
                case DELETE_EXACT_DUPLICATE, ARCHIVE_ORIGINAL, OPTIMIZE, OPTIMIZE_AND_MOVE -> true;
                case CONVERT, CONVERT_AND_MOVE -> plan != null
                        && plan.policy().originalRetention() != OriginalRetentionPolicy.KEEP_ORIGINAL;
                default -> false;
            };
        }

        private void startApply() {
            if (plan == null) return;
            ProfileChoice selectedProfile = profileBox.getValue();
            if (selectedProfile != null && OrganizerProfiles.ANALYZE_ONLY.equals(selectedProfile.profile().id())) {
                appliedPlan = plan.withActions(List.of());
                applyResult = null;
                resultSummary.setText(UiText.text("ui.organizer.result.analysis_only"));
                resultDetails.setText(UiText.format("ui.organizer.result.analysis_only_details", plan.actions().size()));
                undoButton.setDisable(true);
                showPage(5);
                return;
            }

            List<OrganizationPlanItem> selectedItems = previewRows.stream()
                    .filter(PreviewRow::included)
                    .map(PreviewRow::planItem)
                    .toList();
            OrganizationPlan selectedPlan = plan.withActions(selectedItems);
            if (selectedPlan.actions().isEmpty()) {
                dialogs.showWarning(UiText.text("ui.organizer.title"), UiText.text("ui.organizer.preview.nothing_selected"));
                return;
            }
            if (!selectedPlan.executable()) {
                dialogs.showWarning(UiText.text("ui.organizer.title"), UiText.text("ui.organizer.preview.blocking_selected"));
                return;
            }
            long destructive = selectedPlan.actions().stream()
                    .filter(this::isDestructive).count();
            String confirmation = destructive > 0
                    ? UiText.format("ui.organizer.apply.confirm_destructive", selectedPlan.actions().size(), destructive)
                    : UiText.format("ui.organizer.apply.confirm", selectedPlan.actions().size());
            if (!dialogs.showConfirmation(UiText.text("ui.organizer.apply.confirm_title"), null, confirmation)) return;

            appliedPlan = selectedPlan;
            applyResult = null;
            cancelRequested.set(false);
            busy = true;
            applyProgress.setProgress(0);
            applyStatus.setText(UiText.text("ui.organizer.apply.starting"));
            applyCounters.setText("");
            applyCurrentFile.setText("");
            showPage(4);
            updateNavigation();

            applyUiProgress.clear();
            log.info("ORGANIZER_UI_APPLY_START operationId={}", selectedPlan.operationId());
            background.submit(() -> applyUseCase.apply(selectedPlan, cancelRequested::get,
                            applyUiProgress::offer))
                    .whenComplete((value, error) -> UiExecutor.runOnUiThread(() -> {
                        applyUiProgress.clear();
                        busy = false;
                        if (error != null) {
                            Throwable cause = UiExceptionSupport.unwrapAsync(error);
                            applyStatus.setText(UiText.text("ui.organizer.apply.failed") + " " + UiExceptionSupport.message(cause));
                            resultSummary.setText(UiText.text("ui.organizer.result.failed"));
                            log.error("ORGANIZER_UI_APPLY_FAILED operationId=" + selectedPlan.operationId(), cause);
                            resultDetails.setText(UiExceptionSupport.message(cause) + "\n\n"
                                    + UiText.text("ui.organizer.result.failure_help"));
                            undoButton.setDisable(true);
                            showPage(5);
                            return;
                        }
                        applyResult = value;
                        applyProgress.setProgress(1);
                        applyStatus.setText(value.cancelled() ? UiText.text("ui.organizer.apply.cancelled") : UiText.text("ui.organizer.apply.completed"));
                        populateResult(value);
                        undoButton.setDisable(value.committed() <= 0);
                        events.publishEvent(new NavigationRefreshEvent());
                        updateNavigation();
                    }));
        }

        private void updateApplyProgress(OrganizationApplyProgress progress) {
            if (progress == null) return;
            applyStatus.setText(UiText.text("ui.organizer.apply.phase." + progress.phase().name().toLowerCase(Locale.ROOT)));
            if (progress.phase() != OrganizationApplyProgress.Phase.APPLYING) {
                applyProgress.setProgress(progress.phase() == OrganizationApplyProgress.Phase.PREFLIGHT && progress.total() > 0
                        ? Math.min(1.0, (double) progress.checked() / progress.total()) : -1);
                applyCounters.setText(UiText.format("ui.organizer.apply.preflight_counts", progress.checked(), progress.total()));
                applyCurrentFile.setText(progress.currentPath() == null ? "" : progress.currentPath().toString());
                return;
            }
            double ratio = progress.total() <= 0 ? 0 : Math.min(1.0, (double) progress.processed() / progress.total());
            applyProgress.setProgress(ratio);
            applyCounters.setText(UiText.format("ui.organizer.apply.progress_counts", progress.processed(), progress.total(),
                    progress.converted(), progress.moved(), progress.skipped(), progress.conflicts(), progress.failed()));
            applyCurrentFile.setText(progress.currentPath() == null ? "" : progress.currentPath().toString());
        }

        private void populateResult(ApplyResult result) {
            resultSummary.setText(result.cancelled() ? UiText.text("ui.organizer.result.cancelled") : UiText.text("ui.organizer.result.completed"));
            String details = UiText.format("ui.organizer.result.details",
                    result.committed(), result.converted(), result.moved(), result.skipped(), result.conflicts(), result.failed(),
                    result.warnings().size());
            if (result.optimizedEpubs() > 0) {
                details += "\n" + UiText.format("ui.organizer.result.optimization", result.optimizedEpubs(),
                        humanBytes(result.optimizationSourceBytes()), humanBytes(result.optimizationResultBytes()),
                        humanBytes(result.optimizationSavedBytes()), String.format(Locale.ROOT, "%.2f%%", result.optimizationSavedPercent()));
            }
            resultDetails.setText(details);
        }

        private void undoOperation() {
            if (applyResult == null || applyResult.operationId() == null || busy) return;
            if (!dialogs.showConfirmation(UiText.text("ui.organizer.result.undo"), null, UiText.text("ui.organizer.undo.confirm"))) return;
            busy = true;
            undoButton.setDisable(true);
            UUID operationId = applyResult.operationId();
            resultSummary.setText(UiText.text("ui.organizer.undo.running"));
            String expectedCollectionId = appliedPlan == null ? "" : appliedPlan.expectedCollectionId();
            background.submit(() -> undoUseCase.undo(operationId, expectedCollectionId))
                    .whenComplete((restored, error) -> UiExecutor.runOnUiThread(() -> {
                        busy = false;
                        if (error != null) {
                            resultSummary.setText(UiText.text("ui.organizer.undo.failed"));
                            resultDetails.appendText("\n" + UiExceptionSupport.message(error));
                            undoButton.setDisable(false);
                        } else {
                            resultSummary.setText(UiText.format("ui.organizer.undo.completed", restored));
                            undoButton.setDisable(true);
                            events.publishEvent(new NavigationRefreshEvent());
                        }
                        updateNavigation();
                    }));
        }

        private void openDestination() {
            try {
                Path destination = Path.of(destinationField.getText()).toAbsolutePath().normalize();
                if (!Files.isDirectory(destination)) {
                    dialogs.showWarning(UiText.text("ui.organizer.title"), UiText.text("ui.organizer.result.destination_missing"));
                    return;
                }
                if (!Desktop.isDesktopSupported()) {
                    dialogs.showWarning(UiText.text("ui.organizer.title"), UiText.text("ui.organizer.result.open_failed"));
                    return;
                }
                Desktop.getDesktop().open(destination.toFile());
            } catch (Exception error) {
                dialogs.showWarning(UiText.text("ui.organizer.title"), UiText.text("ui.organizer.result.open_failed") + " " + UiExceptionSupport.message(error));
            }
        }

        private void exportReport() {
            if (plan == null) return;
            var file = fileChooserService.chooseFileToSave(stage,
                    UiText.text("ui.organizer.result.export_report"),
                    "myhomelib-organizer-report.csv",
                    List.of(new javafx.stage.FileChooser.ExtensionFilter("CSV (UTF-8)", "*.csv")));
            if (file == null) return;
            try {
                StringBuilder out = new StringBuilder();
                out.append("included;action;source_impact;reason;source;target;source_format;target_format;duplicate;confidence;warnings\n");
                for (PreviewRow row : previewRows) {
                    out.append(row.included()).append(';')
                            .append(csv(row.action())).append(';')
                            .append(csv(row.sourceImpact())).append(';')
                            .append(csv(row.reason())).append(';')
                            .append(csv(row.sourcePath())).append(';')
                            .append(csv(row.targetPath())).append(';')
                            .append(csv(row.sourceFormat())).append(';')
                            .append(csv(row.targetFormat())).append(';')
                            .append(csv(row.duplicate())).append(';')
                            .append(csv(row.confidence())).append(';')
                            .append(csv(row.warnings())).append('\n');
                }
                AtomicFileSupport.writeStringReplacing(file.toPath(), out, StandardCharsets.UTF_8);
            } catch (Exception error) {
                dialogs.showError(UiText.text("ui.organizer.result.export_report"), UiExceptionSupport.message(error));
            }
        }

        private void chooseDirectory(TextField target, String title) {
            java.io.File initialDirectory = null;
            try {
                if (!target.getText().isBlank()) {
                    Path existing = Path.of(target.getText()).toAbsolutePath().normalize();
                    if (Files.isDirectory(existing)) initialDirectory = existing.toFile();
                }
            } catch (Exception ignored) { }
            var selected = fileChooserService.chooseDirectory(stage, title, initialDirectory);
            if (selected != null) target.setText(selected.toPath().toAbsolutePath().normalize().toString());
        }

        private void showPage(int index) {
            pageIndex = Math.max(0, Math.min(pages.size() - 1, index));
            pageHost.getChildren().setAll(pages.get(pageIndex));
            pageTitle.setText(pageTitle(pageIndex));
            stepLabel.setText(UiText.format("ui.organizer.step", pageIndex + 1, pages.size()));
            updateNavigation();
        }

        private void updateNavigation() {
            backButton.setDisable(busy || pageIndex == 0 || (pageIndex == 5 && appliedPlan != null && !appliedPlan.actions().isEmpty()));
            nextButton.setDisable(busy || switch (pageIndex) {
                case 2 -> plan == null;
                case 4 -> applyResult == null;
                default -> false;
            });
            nextButton.setText(switch (pageIndex) {
                case 1 -> UiText.text("ui.organizer.analyze");
                case 3 -> UiText.text("ui.organizer.apply");
                case 5 -> UiText.text("common.close");
                default -> UiText.text("ui.organizer.next");
            });
            closeButton.setText(busy ? UiText.text("ui.organizer.cancel_operation") : UiText.text("common.close"));
            closeButton.setDisable(false);
        }

        private String pageTitle(int index) {
            return switch (index) {
                case 0 -> UiText.text("ui.organizer.page.source");
                case 1 -> UiText.text("ui.organizer.page.rules");
                case 2 -> UiText.text("ui.organizer.page.analysis");
                case 3 -> UiText.text("ui.organizer.page.preview");
                case 4 -> UiText.text("ui.organizer.page.apply");
                case 5 -> UiText.text("ui.organizer.page.result");
                default -> UiText.text("ui.organizer.title");
            };
        }

        private String profileLabel(String id) {
            return switch (id) {
                case OrganizerProfiles.EPUB_LIBRARY -> UiText.text("ui.organizer.profile.epub_library");
                case OrganizerProfiles.KEEP_ORIGINALS -> UiText.text("ui.organizer.profile.keep_originals");
                case OrganizerProfiles.RENAME_ONLY -> UiText.text("ui.organizer.profile.rename_only");
                case OrganizerProfiles.ANALYZE_ONLY -> UiText.text("ui.organizer.profile.analyze_only");
                default -> UiText.text("ui.organizer.profile.safe");
            };
        }

        private String actionLabel(OrganizationAction action) {
            if (action == null) return "";
            return UiText.text("ui.organizer.action." + action.name().toLowerCase(Locale.ROOT));
        }

        private String duplicateLabel(DuplicateKind kind) {
            if (kind == null) return "";
            return UiText.text("ui.organizer.duplicate." + kind.name().toLowerCase(Locale.ROOT));
        }

        private String filterLabel(FilterChoice filter) {
            return UiText.text("ui.organizer.filter." + filter.name().toLowerCase(Locale.ROOT));
        }

        private String optimizationProfileLabel(EpubOptimizationProfile profile) {
            return switch (profile == null ? EpubOptimizationProfile.BALANCED_EPUB33 : profile) {
                case NONE -> UiText.text("ui.settings.converters.optimizer.none");
                case COMPATIBLE_EPUB33 -> UiText.text("ui.settings.converters.optimizer.compatible");
                case BALANCED_EPUB33 -> UiText.text("ui.settings.converters.optimizer.balanced");
                case MAXIMUM_EPUB33 -> UiText.text("ui.settings.converters.optimizer.maximum");
                case MODERN_EPUB34 -> UiText.text("ui.settings.converters.optimizer.modern");
            };
        }

        private String humanBytes(long bytes) {
            long value = Math.max(0L, bytes);
            if (value < 1024) return value + " B";
            double kib = value / 1024.0; if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);
            double mib = kib / 1024.0; if (mib < 1024) return String.format(Locale.ROOT, "%.1f MiB", mib);
            return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
        }

        private VBox pageBox() {
            VBox box = new VBox(10);
            box.setPadding(new Insets(14));
            return box;
        }

        private ScrollPane scroll(Node node) {
            ScrollPane pane = new ScrollPane(node);
            pane.setFitToWidth(true);
            return pane;
        }

        private Node row(String label, Node control) {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(6);
            Label title = new Label(label);
            title.setMinWidth(210);
            GridPane.setHgrow(control, Priority.ALWAYS);
            grid.add(title, 0, 0);
            grid.add(control, 1, 0);
            return grid;
        }

        private Node fieldWithButton(TextField field, Button button) {
            HBox box = new HBox(8, field, button);
            HBox.setHgrow(field, Priority.ALWAYS);
            return box;
        }

        private Label wrap(String text) {
            Label label = new Label(text);
            label.setWrapText(true);
            return label;
        }

        private <T> void selectValue(ComboBox<LabeledValue<T>> combo, T value) {
            combo.getItems().stream().filter(item -> Objects.equals(item.value(), value)).findFirst().ifPresent(combo::setValue);
        }

        private <T> T value(ComboBox<LabeledValue<T>> combo, T fallback) {
            return combo.getValue() == null ? fallback : combo.getValue().value();
        }

        private TableColumn<PreviewRow, String> column(String title, Function<PreviewRow, String> extractor, double width) {
            TableColumn<PreviewRow, String> column = new TableColumn<>(title);
            column.setCellValueFactory(data -> new SimpleStringProperty(extractor.apply(data.getValue())));
            column.setPrefWidth(width);
            return column;
        }

        private String csv(String value) {
            String text = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
            return '"' + text.replace("\"", "\"\"") + '"';
        }

        private final class PreviewRow {
            private final OrganizationPlanItem planItem;
            private final OrganizerAnalysisItem analysisItem;
            private final BooleanProperty included = new SimpleBooleanProperty();

            PreviewRow(OrganizationPlanItem planItem, OrganizerAnalysisItem analysisItem) {
                this.planItem = planItem;
                this.analysisItem = analysisItem;
                this.included.set(safeDefaultSelection());
            }

            OrganizationPlanItem planItem() { return planItem; }
            OrganizerAnalysisItem analysisItem() { return analysisItem; }
            BooleanProperty includedProperty() { return included; }
            boolean included() { return included.get(); }
            void setIncluded(boolean value) { included.set(value); }
            boolean safeDefaultSelection() {
                return !planItem.blocking()
                        && planItem.action() != OrganizationAction.NOOP
                        && planItem.action() != OrganizationAction.KEEP_AS_IS
                        && planItem.action() != OrganizationAction.REVIEW_DUPLICATE
                        && planItem.action() != OrganizationAction.SKIP_UNSUPPORTED
                        && planItem.action() != OrganizationAction.DELETE_EXACT_DUPLICATE;
            }
            String status() { return planItem.blocking() ? UiText.text("ui.organizer.preview.status.blocking") : UiText.text("ui.organizer.preview.status.ready"); }
            String sourcePath() {
                if (planItem.sourcePath() == null) return "";
                return planItem.archiveEntry() ? planItem.sourcePath() + "!/" + planItem.sourceEntry() : planItem.sourcePath().toString();
            }
            String targetPath() { return planItem.targetPath() == null ? "" : planItem.targetPath().toString(); }
            String author() { return analysisItem == null ? "" : analysisItem.identity().firstAuthorDisplayName(); }
            String series() { return analysisItem == null ? "" : analysisItem.identity().series(); }
            String seriesNumber() { return analysisItem == null || analysisItem.identity().seriesNumber() == null ? "" : analysisItem.identity().seriesNumber().toPlainString(); }
            String title() { return analysisItem == null ? "" : analysisItem.identity().title(); }
            String sourceFormat() { return planItem.sourceFormat().toUpperCase(Locale.ROOT); }
            String sourceSize() { return analysisItem == null ? "—" : humanBytes(analysisItem.fingerprint().size()); }
            String optimizationProfile() {
                boolean epub = "epub".equalsIgnoreCase(planItem.targetFormat());
                boolean optimized = planItem.action() == OrganizationAction.OPTIMIZE || planItem.action() == OrganizationAction.OPTIMIZE_AND_MOVE || (planItem.conversionRequired() && epub);
                return optimized ? optimizationProfileLabel(value(epubOptimization, EpubOptimizationProfile.BALANCED_EPUB33)) : "—";
            }
            String optimizationResult() { return optimizationProfile().equals("—") ? "—" : UiText.text("ui.organizer.preview.pending"); }
            String estimatedSavings() { return "—"; }
            String compatibilityWarning() {
                return value(epubOptimization, EpubOptimizationProfile.BALANCED_EPUB33) == EpubOptimizationProfile.MODERN_EPUB34
                        && !optimizationProfile().equals("—") ? UiText.text("ui.organizer.preview.modern_warning") : "";
            }
            String targetFormat() { return planItem.targetFormat().toUpperCase(Locale.ROOT); }
            String action() { return actionLabel(planItem.action()); }
            String sourceImpact() {
                return isDestructive(planItem)
                        ? UiText.text("ui.organizer.preview.source_impact.changed")
                        : UiText.text("ui.organizer.preview.source_impact.preserved");
            }
            String reason() {
                String key = switch (planItem.action()) {
                    case NOOP -> "ui.organizer.reason.noop";
                    case KEEP_AS_IS -> "ui.organizer.reason.keep";
                    case RENAME -> "ui.organizer.reason.rename";
                    case MOVE -> "ui.organizer.reason.move";
                    case MOVE_AND_RENAME -> "ui.organizer.reason.move_rename";
                    case CONVERT, CONVERT_AND_MOVE -> "ui.organizer.reason.convert";
                    case OPTIMIZE, OPTIMIZE_AND_MOVE -> "ui.organizer.reason.optimize";
                    case EXTRACT -> "ui.organizer.reason.extract";
                    case ARCHIVE_ORIGINAL -> "ui.organizer.reason.archive_original";
                    case DELETE_EXACT_DUPLICATE -> "ui.organizer.reason.delete_exact_duplicate";
                    case REVIEW_DUPLICATE -> "ui.organizer.reason.review_duplicate";
                    case SKIP_UNSUPPORTED -> "ui.organizer.reason.skip_unsupported";
                    case CONFLICT -> "ui.organizer.reason.conflict";
                    case ERROR -> "ui.organizer.reason.error";
                };
                return UiText.text(key);
            }
            String duplicate() {
                String label = duplicateLabel(planItem.duplicate().kind());
                String matched = planItem.duplicate().matchedLabel();
                return matched.isBlank() ? label : label + " → " + matched;
            }
            String confidence() { return planItem.confidence() + "%"; }
            String warnings() {
                List<String> messages = new ArrayList<>();
                messages.addAll(planItem.warnings());
                planItem.issues().forEach(issue -> messages.add(issue.message()));
                return String.join(" | ", messages);
            }
        }
    }

    private record AnalysisAndPlan(OrganizationAnalysis analysis, OrganizationPlan plan) { }

    private record AnalysisSourceKey(Path sourcePath, String sourceEntry) {
        private AnalysisSourceKey {
            sourcePath = sourcePath == null ? Path.of("") : sourcePath.toAbsolutePath().normalize();
            sourceEntry = sourceEntry == null ? "" : sourceEntry;
        }
    }

    private record ProfileChoice(OrganizerProfile profile, String label) {
        @Override public String toString() { return label; }
    }

    private record LabeledValue<T>(T value, String label) {
        @Override public String toString() { return label; }
    }

    private enum FilterChoice {
        ALL, ERRORS, CONFLICTS, CONVERSIONS, DUPLICATES, LOW_CONFIDENCE, PDF, UNKNOWN_AUTHOR, UNKNOWN_SERIES
    }
}
