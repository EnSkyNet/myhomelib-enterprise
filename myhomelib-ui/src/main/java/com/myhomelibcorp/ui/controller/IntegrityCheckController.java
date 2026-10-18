package com.myhomelibcorp.ui.controller;


import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.health.LibraryHealthIssue;
import com.myhomelibcorp.application.health.LibraryHealthIssueType;
import com.myhomelibcorp.application.health.LibraryHealthReport;
import com.myhomelibcorp.application.health.LibraryHealthSeverity;
import com.myhomelibcorp.application.health.LibraryHealthService;
import com.myhomelibcorp.application.content.index.ContentIndexHealth;
import com.myhomelibcorp.application.content.maintenance.ContentIndexMaintenanceService;
import com.myhomelibcorp.application.content.maintenance.ContentIndexRebuildProgress;
import com.myhomelibcorp.application.content.maintenance.ContentIndexRebuildResult;
import com.myhomelibcorp.application.integrity.ArtifactIntegrityFinding;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Unified MHL-117 Library Health dashboard backed by the non-destructive MHL-116 artifact audit. */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrityCheckController {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private final LibraryHealthService healthService;
    private final DialogService dialogService;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;
    private final ApplicationState appState;
    private final ContentIndexMaintenanceService contentIndexMaintenance;
    private final FileChooserService fileChooserService;

    @FXML private Button checkButton;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Label statusLabel;
    @FXML private Label overallStatusLabel;
    @FXML private Label localArtifactsValue;
    @FXML private Label missingValue;
    @FXML private Label corruptValue;
    @FXML private Label changedValue;
    @FXML private Label duplicatesValue;
    @FXML private Label metadataValue;
    @FXML private Label indexValue;
    @FXML private Label backupValue;
    @FXML private Label contentIndexValue;
    @FXML private Label contentIndexDetailLabel;
    @FXML private ProgressBar contentIndexProgress;
    @FXML private Button contentIndexRebuildButton;
    @FXML private Button contentIndexCancelButton;
    @FXML private TableView<LibraryHealthIssue> issueTable;
    @FXML private TableColumn<LibraryHealthIssue, String> severityColumn;
    @FXML private TableColumn<LibraryHealthIssue, String> categoryColumn;
    @FXML private TableColumn<LibraryHealthIssue, Number> countColumn;
    @FXML private TableColumn<LibraryHealthIssue, String> actionColumn;
    @FXML private TextArea detailArea;

    private LibraryHealthReport lastReport;
    private String lastReportText = "";
    private final AtomicBoolean contentIndexCancel = new AtomicBoolean();
    private final AtomicLong contentIndexHealthGeneration = new AtomicLong();
    private Future<?> contentIndexRebuildTask;

    @FXML
    public void initialize() {
        progressIndicator.setVisible(false);
        statusLabel.setText(UiText.text("ui.fxml.integrity_check.ready_to_check"));
        overallStatusLabel.setText(UiText.text("ui.fxml.integrity_check.health_has_not_been_checked_yet"));
        configureIssueTable();
        if (contentIndexProgress != null) { contentIndexProgress.setProgress(0); contentIndexProgress.setVisible(false); contentIndexProgress.setManaged(false); }
        if (contentIndexCancelButton != null) { contentIndexCancelButton.setDisable(true); }
        refreshContentIndexHealth();
        detailArea.setText(UiText.text("ui.java.integrity_check_controller.24e7f16eba"));
    }

    private void configureIssueTable() {
        severityColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(issueSeverity(cell.getValue().severity())));
        categoryColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(issueTitle(cell.getValue())));
        countColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().count()));
        actionColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(issueAction(cell.getValue())));
        issueTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, issue) -> {
            if (issue != null) displayIssueDetail(issue);
        });
        issueTable.setPlaceholder(new Label(UiText.text("ui.java.integrity_check_controller.0fc7d16e90")));
    }

    @FXML
    public void onCheckIntegrity() {
        checkButton.setDisable(true);
        progressIndicator.setVisible(true);
        statusLabel.setText(UiText.text("ui.java.integrity_check_controller.e8776af5b7"));
        detailArea.setText(UiText.text("ui.java.integrity_check_controller.2de4c5ad60"));

        var collection = appState.getCurrentLibraryCollection();
        String operationId = operationCenter.start(
                UiText.text("ui.java.integrity_check_controller.6add810547"), collection == null ? "" : collection.getId(),
                OperationStage.INTEGRITY_CHECKS, false);
        String expectedCollectionId = collection == null ? "" : collection.getId();
        executor.submit(() -> healthService.refresh(expectedCollectionId))
                .whenComplete((report, error) -> {
                    Throwable cause = error == null ? null : UiExceptionSupport.unwrapAsync(error);
                    if (cause != null) {
                        operationCenter.fail(operationId, cause);
                        log.error("Library Health refresh failed", cause);
                    } else if (report != null) {
                        operationCenter.complete(operationId, report.healthy()
                                ? UiText.text("ui.java.integrity_check_controller.0fc7d16e90")
                                : UiText.text("ui.java.integrity_check_controller.a355f59c6a") + formatNumber(report.totalProblemCount()));
                    }
                    UiExecutor.runOnUiThread(() -> {
                        checkButton.setDisable(false);
                        progressIndicator.setVisible(false);
                        if (!expectedCollectionId.equals(currentCollectionId())) {
                            log.debug("Пропущено stale Library Health callback для колекції {}", expectedCollectionId);
                            return;
                        }
                        if (cause != null) {
                            statusLabel.setText(UiText.text("ui.java.integrity_check_controller.8af7f555ee") + UiExceptionSupport.message(cause));
                            dialogService.showError(UiText.text("ui.fxml.integrity_check.library_health"), UiText.text("ui.java.integrity_check_controller.a94dc0c508") + UiExceptionSupport.message(cause));
                            return;
                        }
                        if (report == null) return;
                        lastReport = report;
                        lastReportText = formatExport(report);
                        displayReport(report);
                        statusLabel.setText(UiText.text("ui.java.integrity_check_controller.239ef7a7ef") + DATE_TIME.format(report.generatedAt()));
                    });
                });
    }

    private void displayReport(LibraryHealthReport report) {
        localArtifactsValue.setText(formatNumber(report.localArtifacts()));
        missingValue.setText(formatNumber(report.missingArtifacts()));
        corruptValue.setText(formatNumber(report.corruptArtifacts()));
        changedValue.setText(formatNumber(report.changedArtifacts()));
        duplicatesValue.setText(formatNumber(report.duplicateBooks()));
        metadataValue.setText(formatNumber(report.metadataGaps()));
        indexValue.setText(report.searchIndexFresh() ? UiText.text("ui.java.integrity_check_controller.3a3b727384") : UiText.text("ui.java.integrity_check_controller.233c4ce3cf"));
        backupValue.setText(report.latestBackupAt() == null
                ? UiText.text("ui.java.integrity_check_controller.940999bb7d")
                : formatAge(report.backupAgeHours()));

        if (report.healthy()) {
            overallStatusLabel.setText(UiText.text("ui.java.integrity_check_controller.8f35fe630f"));
            overallStatusLabel.setStyle("-fx-text-fill: -mhl-success; -fx-font-weight: bold; -fx-font-size: 15px;");
        } else {
            overallStatusLabel.setText(UiText.text("ui.java.integrity_check_controller.1d23bcd31e") + formatNumber(report.totalProblemCount()));
            overallStatusLabel.setStyle("-fx-text-fill: -mhl-warning; -fx-font-weight: bold; -fx-font-size: 15px;");
        }

        issueTable.setItems(FXCollections.observableArrayList(report.issues()));
        if (report.issues().isEmpty()) {
            detailArea.setText(UiText.text("ui.java.integrity_check_controller.af60344509")
                    + UiText.text("ui.java.integrity_check_controller.f9093ad4f2") + formatNumber(report.auditInspected())
                    + UiText.text("ui.java.integrity_check_controller.fe967cf0aa") + formatNumber(report.auditReused())
                    + UiText.text("ui.java.integrity_check_controller.56e687535b") + formatBytes(report.auditBytesRead()) + ".");
        } else {
            issueTable.getSelectionModel().selectFirst();
        }
    }

    private void displayIssueDetail(LibraryHealthIssue issue) {
        if (lastReport == null) return;
        StringBuilder text = new StringBuilder();
        text.append(issueTitle(issue)).append("\n")
                .append(UiText.text("ui.java.integrity_check_controller.9770b04d87")).append(formatNumber(issue.count())).append("\n\n")
                .append(issueDetail(issue)).append("\n\n")
                .append(UiText.text("ui.java.integrity_check_controller.524519e47c")).append(issueAction(issue));

        List<ArtifactIntegrityFinding> matches = artifactFindings(issue.type());
        if (!matches.isEmpty()) {
            text.append(UiText.text("ui.java.integrity_check_controller.29008ba6f7"));
            for (ArtifactIntegrityFinding finding : matches) {
                text.append("• ").append(userFacingFindingLabel(finding))
                        .append(" [").append(findingStatus(finding.status())).append("]");
                String findingDetail = findingDetail(finding);
                if (!findingDetail.isBlank()) text.append("\n  ").append(findingDetail);
                text.append('\n');
            }
        }
        detailArea.setText(text.toString());
    }

    private List<ArtifactIntegrityFinding> artifactFindings(LibraryHealthIssueType type) {
        if (lastReport == null) return List.of();
        return lastReport.artifactFindings().stream().filter(finding -> switch (type) {
            case MISSING_ARTIFACTS -> finding.status().name().equals("MISSING");
            case CORRUPT_ARTIFACTS -> finding.status().isCorrupt();
            case CHANGED_ARTIFACTS -> finding.status().isChanged();
            default -> false;
        }).limit(200).toList();
    }


    @FXML
    public void onShowContentIndex() {
        requestContentIndexHealth(true);
    }

    @FXML
    public void onRebuildContentIndex() {
        String id = currentCollectionId();
        if (id.isBlank()) {
            dialogService.showWarning(UiText.text("ui.fxml.integrity_check.full_text_index"), UiText.text("ui.java.integrity_check_controller.580c801604"));
            return;
        }
        if (!dialogService.showConfirmation(UiText.text("ui.java.integrity_check_controller.a225550297"),
                UiText.text("ui.java.integrity_check_controller.08dc0c3c01"),
                UiText.text("ui.java.integrity_check_controller.a9995e6b95"))) return;

        contentIndexCancel.set(false);
        setContentRebuildRunning(true);
        updateContentProgress(new ContentIndexRebuildProgress(0, 0, "", "starting"));
        String operationTitle = UiText.text("ui.java.integrity_check_controller.a225550297");
        String operationId = operationCenter.start(operationTitle, id, OperationKind.INDEX_REBUILD,
                OperationStage.UPDATING_SEARCH_INDEX, true);
        contentIndexRebuildTask = executor.submit(() -> contentIndexMaintenance.rebuild(
                id, contentIndexCancel::get, progress -> {
                    OperationProgress telemetry = OperationProgress.stage(
                                    operationId, OperationStage.UPDATING_SEARCH_INDEX, true)
                            .withProgress(progress.processedBooks(), progress.totalBooks())
                            .withCurrentItem(progress.currentBook().isBlank() ? progress.phase() : progress.currentBook());
                    operationCenter.accept(operationTitle, id, OperationKind.INDEX_REBUILD, telemetry);
                    UiExecutor.runOnUiThread(() -> {
                        if (id.equals(currentCollectionId())) updateContentProgress(progress);
                    });
                }))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        operationCenter.fail(operationId, UiExceptionSupport.unwrapAsync(error));
                    } else if (result == null) {
                        operationCenter.fail(operationId, UiText.text("ui.java.integrity_check_controller.fdae6ff7bb"));
                    } else if (result.status() == ContentIndexRebuildResult.Status.COMPLETED) {
                        operationCenter.complete(operationId, UiText.text("ui.java.integrity_check_controller.16b100ce7b") + result.processedBooks()
                                + UiText.text("ui.java.integrity_check_controller.6b1ae281cd") + result.indexedArtifacts());
                    } else if (result.status() == ContentIndexRebuildResult.Status.CANCELLED) {
                        operationCenter.cancel(operationId, UiText.text("ui.java.integrity_check_controller.eb9d9b891e"));
                    } else {
                        if (!result.technicalDetail().isBlank()) {
                            log.error("Content-index rebuild failed: {}", result.technicalDetail());
                        }
                        operationCenter.fail(operationId, UiText.text("common.error.operation_failed"));
                    }
                    UiExecutor.runOnUiThread(() -> {
                        setContentRebuildRunning(false);
                        if (!id.equals(currentCollectionId())) {
                            log.debug("Пропущено stale content-index rebuild callback для колекції {}", id);
                            return;
                        }
                        if (error != null) {
                            Throwable cause = UiExceptionSupport.unwrapAsync(error);
                            dialogService.showError(UiText.text("ui.fxml.integrity_check.full_text_index"), UiText.text("ui.java.integrity_check_controller.d503cc2bf1") + UiExceptionSupport.message(cause));
                            refreshContentIndexHealth();
                            return;
                        }
                        if (result == null) return;
                        displayContentIndexHealth(result.health());
                        if (result.status() == ContentIndexRebuildResult.Status.COMPLETED) {
                            detailArea.setText(UiText.text("ui.java.integrity_check_controller.dbe578013c")
                                    + UiText.text("ui.java.integrity_check_controller.455cd40c95") + formatNumber(result.processedBooks()) + "\n"
                                    + UiText.text("ui.java.integrity_check_controller.c191914616") + formatNumber(result.indexedArtifacts()) + "\n\n"
                                    + formatContentIndexHealth(result.health()));
                        } else if (result.status() == ContentIndexRebuildResult.Status.CANCELLED) {
                            detailArea.setText(UiText.text("ui.java.integrity_check_controller.138034c7f1")
                                    + formatContentIndexHealth(result.health()));
                        } else {
                            detailArea.setText(UiText.text("ui.java.integrity_check_controller.69efb4ac0a")
                                    + UiText.text("common.error.operation_failed") + "\n"
                                    + UiText.text("common.error.details_in_log") + "\n\n"
                                    + formatContentIndexHealth(result.health()));
                        }
                    });
                });
    }

    @FXML
    public void onCancelContentIndexRebuild() {
        contentIndexCancel.set(true);
        if (contentIndexCancelButton != null) contentIndexCancelButton.setDisable(true);
        if (contentIndexDetailLabel != null) contentIndexDetailLabel.setText(UiText.text("ui.java.integrity_check_controller.d553b63991"));
    }

    private void refreshContentIndexHealth() {
        requestContentIndexHealth(false);
    }

    private void requestContentIndexHealth(boolean showDetail) {
        long generation = contentIndexHealthGeneration.incrementAndGet();
        String id = currentCollectionId();
        if (id.isBlank()) {
            if (contentIndexValue != null) contentIndexValue.setText("—");
            if (contentIndexDetailLabel != null) contentIndexDetailLabel.setText(UiText.text("ui.java.integrity_check_controller.8477ba2961"));
            return;
        }
        executor.submit(() -> contentIndexMaintenance.health(id))
                .thenAccept(health -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrentContentIndexRequest(id, generation)) return;
                    displayContentIndexHealth(health);
                    if (showDetail) detailArea.setText(formatContentIndexHealth(health));
                }))
                .exceptionally(error -> {
                    UiExecutor.runOnUiThread(() -> {
                        if (!isCurrentContentIndexRequest(id, generation)) return;
                        if (contentIndexValue != null) contentIndexValue.setText(UiText.text("common.error"));
                        if (contentIndexDetailLabel != null) contentIndexDetailLabel.setText(UiExceptionSupport.message(error));
                    });
                    return null;
                });
    }

    private boolean isCurrentContentIndexRequest(String collectionId, long generation) {
        return generation == contentIndexHealthGeneration.get() && collectionId.equals(currentCollectionId());
    }

    private void displayContentIndexHealth(ContentIndexHealth health) {
        if (health == null) return;
        if (contentIndexValue != null) contentIndexValue.setText(
                health.state() == ContentIndexHealth.State.READY
                        ? "OK"
                        : UiText.text("ui.java.integrity_check_controller.217f5b5d3c"));
        if (contentIndexDetailLabel != null) contentIndexDetailLabel.setText(
                "v" + health.schemaVersion() + UiText.text("ui.java.integrity_check_controller.653fb39e1b") + formatNumber(health.documentCount()) + " · " + formatBytes(health.sizeBytes()));
    }

    private String formatContentIndexHealth(ContentIndexHealth health) {
        if (health == null) return UiText.text("ui.java.integrity_check_controller.7a32e9c7f4");
        return UiText.text("ui.java.integrity_check_controller.2fd8ee5431")
                + UiText.text("ui.java.integrity_check_controller.ce86b580b9") + currentCollectionName() + "\n"
                + UiText.text("ui.java.integrity_check_controller.e2998e14d1") + health.schemaVersion() + "\n"
                + UiText.text("ui.java.integrity_check_controller.9c6f189d5e") + formatNumber(health.documentCount()) + "\n"
                + UiText.text("ui.java.integrity_check_controller.173f73a83f") + formatBytes(health.sizeBytes()) + "\n"
                + UiText.text("ui.java.integrity_check_controller.bfda372c9d") + (health.compatible() ? UiText.text("ui.java.integrity_check_controller.a7b474c9aa") : UiText.text("ui.java.integrity_check_controller.d2e18402de")) + "\n"
                + UiText.text("ui.java.integrity_check_controller.384f693f20") + contentIndexStateText(health) + "\n\n"
                + UiText.text("ui.java.integrity_check_controller.64b1151103");
    }

    private String contentIndexStateText(ContentIndexHealth health) {
        if (health == null) return UiText.text("common.result.unknown");
        if (!health.technicalDetail().isBlank()) {
            log.warn("Content-index health detail for {}: {}", health.collectionId(), health.technicalDetail());
        }
        String key = switch (health.state()) {
            case ABSENT -> "ui.integrity.content_index.state.absent";
            case EMPTY -> "ui.integrity.content_index.state.empty";
            case READY -> "ui.integrity.content_index.state.ready";
            case STALE -> "ui.integrity.content_index.state.stale";
            case SCHEMA_MISMATCH -> "ui.integrity.content_index.state.schema_mismatch";
            case ERROR -> "ui.integrity.content_index.state.error";
        };
        String value = UiText.text(key);
        return health.technicalDetail().isBlank() ? value : value + " " + UiText.text("common.error.details_in_log");
    }

    private void updateContentProgress(ContentIndexRebuildProgress progress) {
        if (progress == null) return;
        if (contentIndexProgress != null) {
            double value = progress.totalBooks() <= 0 ? ProgressIndicator.INDETERMINATE_PROGRESS
                    : Math.min(1.0, progress.processedBooks() / (double) progress.totalBooks());
            contentIndexProgress.setProgress(value);
        }
        if (contentIndexDetailLabel != null) {
            String current = progress.currentBook().isBlank() ? "" : " · " + progress.currentBook();
            contentIndexDetailLabel.setText(contentIndexPhaseText(progress.phase()) + " · " + progress.processedBooks() + "/" + progress.totalBooks() + current);
        }
    }

    private static String contentIndexPhaseText(String phase) {
        if (phase == null || phase.isBlank()) return UiText.text("ui.import.stage.processing");
        return switch (phase.toLowerCase(java.util.Locale.ROOT)) {
            case "starting" -> UiText.text("ui.java.integrity_check_controller.3f65fd1c44");
            case "extracting" -> UiText.text("ui.java.integrity_check_controller.5ad513428b");
            case "extracted" -> UiText.text("ui.java.integrity_check_controller.7b06b00769");
            case "complete" -> UiText.text("ui.import.stage.completed");
            default -> UiText.text("ui.import.stage.processing");
        };
    }

    private void setContentRebuildRunning(boolean running) {
        if (contentIndexRebuildButton != null) contentIndexRebuildButton.setDisable(running);
        if (contentIndexCancelButton != null) contentIndexCancelButton.setDisable(!running);
        if (contentIndexProgress != null) { contentIndexProgress.setVisible(running); contentIndexProgress.setManaged(running); }
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null || collection.getId() == null ? "" : collection.getId().trim();
    }

    private String currentCollectionName() {
        var collection = appState.getCurrentLibraryCollection();
        if (collection == null || collection.getName() == null || collection.getName().isBlank()) {
            return UiText.text("ui.bootstrap.no_collection");
        }
        return collection.getName().trim();
    }

    @FXML public void onShowMissing() { selectIssue(LibraryHealthIssueType.MISSING_ARTIFACTS); }
    @FXML public void onShowCorrupt() { selectIssue(LibraryHealthIssueType.CORRUPT_ARTIFACTS); }
    @FXML public void onShowChanged() { selectIssue(LibraryHealthIssueType.CHANGED_ARTIFACTS); }
    @FXML public void onShowDuplicates() { selectIssue(LibraryHealthIssueType.DUPLICATES); }
    @FXML public void onShowMetadata() { selectIssue(LibraryHealthIssueType.METADATA_GAPS); }
    @FXML public void onShowIndex() { selectIssue(LibraryHealthIssueType.STALE_SEARCH_INDEX); }
    @FXML public void onShowBackup() { selectIssue(LibraryHealthIssueType.BACKUP_AGE); }

    private void selectIssue(LibraryHealthIssueType type) {
        if (lastReport == null) return;
        for (LibraryHealthIssue issue : issueTable.getItems()) {
            if (issue.type() == type) {
                issueTable.getSelectionModel().select(issue);
                issueTable.scrollTo(issue);
                return;
            }
        }
        detailArea.setText(noIssueMessage(type));
    }

    private String noIssueMessage(LibraryHealthIssueType type) {
        return switch (type) {
            case MISSING_ARTIFACTS -> UiText.text("ui.java.integrity_check_controller.103f95bec8");
            case CORRUPT_ARTIFACTS -> UiText.text("ui.java.integrity_check_controller.53eb692df9");
            case CHANGED_ARTIFACTS -> UiText.text("ui.java.integrity_check_controller.edef519a76");
            case DUPLICATES -> UiText.text("ui.java.integrity_check_controller.a7bc2a72f0");
            case METADATA_GAPS -> UiText.text("ui.java.integrity_check_controller.c2b6be4f7c");
            case STALE_SEARCH_INDEX -> UiText.text("ui.java.integrity_check_controller.ad1d840b80") + lastReport.searchIndexDetail();
            case BACKUP_AGE -> lastReport.latestBackupAt() == null
                    ? UiText.text("ui.java.integrity_check_controller.31e54999d8")
                    : UiText.text("ui.java.integrity_check_controller.24211df359") + DATE_TIME.format(lastReport.latestBackupAt())
                    + " (" + formatAge(lastReport.backupAgeHours()) + ").";
            case DATABASE_INTEGRITY -> UiText.text("ui.java.integrity_check_controller.8d021f3c6a");
        };
    }

    @FXML
    public void onSafeRepairInfo() {
        dialogService.showInfo(UiText.text("ui.fxml.integrity_check.safe_maintenance"),
                UiText.text("ui.java.integrity_check_controller.2cd320f090")
                        + UiText.text("ui.java.integrity_check_controller.a454642308")
                        + UiText.text("ui.java.integrity_check_controller.1d218a9434"));
    }

    @FXML
    public void onExportReport() {
        if (lastReportText == null || lastReportText.isBlank()) {
            dialogService.showWarning(UiText.text("ui.fxml.integrity_check.export"), UiText.text("ui.java.integrity_check_controller.821faba7dc"));
            return;
        }
        File target = fileChooserService.chooseFileToSave(
                detailArea.getScene() == null ? null : detailArea.getScene().getWindow(),
                UiText.text("ui.java.integrity_check_controller.48c91023ee"),
                "myhomelib-library-health.txt",
                List.of(new FileChooser.ExtensionFilter(UiText.text("ui.java.integrity_check_controller.ca4976f2f4"), "*.txt")));
        if (target == null) return;
        try {
            AtomicFileSupport.writeStringReplacing(target.toPath(), lastReportText, StandardCharsets.UTF_8);
            dialogService.showInfo(UiText.text("ui.fxml.integrity_check.export"), UiText.text("ui.java.integrity_check_controller.8cb6c2a260") + target.getAbsolutePath());
        } catch (IOException error) {
            log.error("Cannot export Library Health report", error);
            dialogService.showError(UiText.text("ui.fxml.integrity_check.export"), UiText.text("ui.java.integrity_check_controller.59039a4669") + UiExceptionSupport.message(error));
        }
    }

    private String formatExport(LibraryHealthReport report) {
        StringBuilder out = new StringBuilder();
        out.append(UiText.text("ui.java.integrity_check_controller.0f094e575b"))
                .append(UiText.text("ui.java.integrity_check_controller.64c021dd17")).append(DATE_TIME.format(report.generatedAt())).append("\n\n")
                .append(UiText.text("ui.java.integrity_check_controller.0c7569b71b")).append(report.localArtifacts()).append('\n')
                .append(UiText.text("ui.java.integrity_check_controller.b0802e4ec0")).append(report.missingArtifacts()).append('\n')
                .append(UiText.text("ui.java.integrity_check_controller.4a474d1e85")).append(report.corruptArtifacts()).append('\n')
                .append(UiText.text("ui.java.integrity_check_controller.26ea56e3d8")).append(report.changedArtifacts()).append('\n')
                .append(UiText.text("ui.java.integrity_check_controller.2fd3f35fcc")).append(report.duplicateBooks()).append('\n')
                .append(UiText.text("ui.java.integrity_check_controller.bc4f0d8676")).append(report.metadataGaps()).append('\n')
                .append(UiText.text("ui.java.integrity_check_controller.49b40f5748")).append(report.databaseHealthy() ? UiText.text("ui.java.integrity_check_controller.72f2b391c6") : UiText.text("ui.java.integrity_check_controller.5d7bd00d31")).append('\n')
                .append(UiText.text("ui.java.integrity_check_controller.99c2e391d0")).append(report.searchIndexFresh() ? UiText.text("ui.java.integrity_check_controller.3dbf105653") : UiText.text("ui.java.integrity_check_controller.f4d671573a"))
                .append(" (").append(UiText.format("ui.integrity.search_counts", report.catalogBooks(), report.indexedDocuments())).append(")\n")
                .append(UiText.text("ui.java.integrity_check_controller.e75733236d")).append(report.latestBackupAt() == null ? UiText.text("ui.java.integrity_check_controller.c0f724ad68") : DATE_TIME.format(report.latestBackupAt()))
                .append(UiText.text("ui.java.integrity_check_controller.ff1b19ccdd")).append(report.backupAgeHours()).append('\n')
                .append(UiText.text("ui.java.integrity_check_controller.ca711c3dab")).append(report.auditInspected())
                .append(UiText.text("ui.java.integrity_check_controller.0a64074314")).append(report.auditReused())
                .append(UiText.text("ui.java.integrity_check_controller.7b45ab72db")).append(report.auditBytesRead()).append("\n\n");

        out.append(UiText.text("ui.java.integrity_check_controller.e8fc6b2124"));
        if (report.issues().isEmpty()) out.append(UiText.text("ui.java.integrity_check_controller.3b4c9c4502"));
        for (LibraryHealthIssue issue : report.issues()) {
            out.append(issueSeverity(issue.severity())).append(" | ").append(issueTitle(issue)).append(UiText.text("ui.java.integrity_check_controller.4ff1fd2fa5"))
                    .append(issue.count()).append('\n')
                    .append("  ").append(issueDetail(issue)).append('\n')
                    .append(UiText.text("ui.java.integrity_check_controller.87e6f934e1")).append(issueAction(issue)).append('\n');
        }

        if (!report.artifactFindings().isEmpty()) {
            out.append(UiText.text("ui.java.integrity_check_controller.28c3515a59"));
            for (ArtifactIntegrityFinding finding : report.artifactFindings()) {
                // Diagnostic UUIDs and hashes belong in the support log, not in
                // the report opened by a reader or exported to share with others.
                log.debug("Integrity diagnostic: artifact={}, book={}, path={}, expectedSha={}, observedSha={}",
                        finding.artifactId(), finding.bookId(), finding.path(),
                        finding.baselineSha256(), finding.observedSha256());
                out.append(findingStatus(finding.status())).append(" | ")
                        .append(userFacingFindingLabel(finding)).append('\n');
                String findingDetail = findingDetail(finding);
                if (!findingDetail.isBlank()) out.append("  ").append(findingDetail).append('\n');
            }
        }
        return out.toString();
    }

    private String issueSeverity(LibraryHealthSeverity severity) {
        return switch (severity) {
            case ERROR -> UiText.text("common.error");
            case WARNING -> UiText.text("common.warning");
            case INFO -> UiText.text("ui.java.integrity_check_controller.455cfc2d2f");
        };
    }

    private String issueTitle(LibraryHealthIssue issue) {
        return UiText.text("ui.integrity.issue." + issue.type().name().toLowerCase(Locale.ROOT) + ".title");
    }

    private String issueAction(LibraryHealthIssue issue) {
        return UiText.text("ui.integrity.issue." + issue.type().name().toLowerCase(Locale.ROOT) + ".action");
    }

    private String issueDetail(LibraryHealthIssue issue) {
        String base = UiText.text("ui.integrity.issue." + issue.type().name().toLowerCase(Locale.ROOT) + ".detail");
        return switch (issue.type()) {
            case DATABASE_INTEGRITY -> {
                if (!issue.detail().isBlank()) {
                    log.warn("Database integrity technical detail: {}", issue.detail());
                    yield base + "\n" + UiText.text("common.error.details_in_log");
                }
                yield base;
            }
            case STALE_SEARCH_INDEX -> lastReport == null
                    ? base : base + "\n" + UiText.format("ui.integrity.search_counts", lastReport.catalogBooks(), lastReport.indexedDocuments());
            case BACKUP_AGE -> lastReport != null && lastReport.latestBackupAt() != null
                    ? base + " " + lastReport.backupAgeHours() + " " + UiText.text("ui.integrity.hours")
                    : UiText.text("ui.integrity.issue.backup_age.detail_missing");
            default -> base;
        };
    }

    /** A readable title and optional filename, without internal IDs, hashes or absolute paths. */
    static String userFacingFindingLabel(ArtifactIntegrityFinding finding) {
        String title = finding.bookTitle().isBlank()
                ? UiText.text("ui.integrity.book_without_title") : finding.bookTitle().strip();
        String source = finding.path().replace('\\', '/');
        int slash = source.lastIndexOf('/');
        String filename = source.substring(slash + 1).strip();
        return filename.isBlank() ? title : title + " — " + filename;
    }

    private String findingStatus(com.myhomelibcorp.application.integrity.ArtifactIntegrityStatus status) {
        return UiText.text("ui.integrity.finding.status." + status.name().toLowerCase(Locale.ROOT));
    }

    private String findingDetail(ArtifactIntegrityFinding finding) {
        return switch (finding.status()) {
            case OK -> "";
            case MISSING -> UiText.text("ui.integrity.finding.detail.missing");
            case HASH_CHANGED -> UiText.text("ui.integrity.finding.detail.hash_changed");
            case SIZE_CHANGED -> UiText.text("ui.integrity.finding.detail.size_changed")
                    + finding.baselineSizeBytes() + " → " + finding.observedSizeBytes() + " " + UiText.text("ui.integrity.bytes");
            case UNREADABLE -> findingDetailWithTechnicalLog(finding,
                    UiText.text("ui.integrity.finding.detail.unreadable"));
            case CORRUPT_ARCHIVE -> findingDetailWithTechnicalLog(finding,
                    UiText.text("ui.integrity.finding.detail.corrupt_archive"));
        };
    }

    private String findingDetailWithTechnicalLog(ArtifactIntegrityFinding finding, String base) {
        if (finding.detail().isBlank()) return base;
        log.warn("Artifact integrity technical detail for {}: {}", finding.artifactId(), finding.detail());
        return base + "\n" + UiText.text("common.error.details_in_log");
    }

    private static String formatNumber(long number) {
        return number < 0 ? "—" : String.format(Locale.ROOT, "%,d", number).replace(',', ' ');
    }

    private static String formatAge(long hours) {
        if (hours < 0) return "—";
        long days = hours / 24;
        long remainder = hours % 24;
        return days > 0 ? days + UiText.text("ui.java.integrity_check_controller.cc96a502e1") + remainder + UiText.text("ui.java.integrity_check_controller.1bcf62b666") : hours + UiText.text("ui.java.integrity_check_controller.1bcf62b666");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);
        double mib = kib / 1024.0;
        if (mib < 1024) return String.format(Locale.ROOT, "%.1f MiB", mib);
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }

    @FXML
    public void closeDialog() {
        Stage stage = (Stage) detailArea.getScene().getWindow();
        if (stage != null) stage.close();
    }
}
