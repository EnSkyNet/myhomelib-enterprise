package com.myhomelibcorp.ui.collection;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.collection.CollectionMaintenanceReport;
import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.usecase.collection.CollectionMaintenanceUseCase;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Owns Analyze -> Preview -> Dry run -> Apply behavior for the collection maintenance panel. */
@Component
@RequiredArgsConstructor
@Slf4j
final class CollectionMaintenancePanelCoordinator {
    private final CollectionMaintenanceUseCase maintenanceUseCase;
    private final ApplicationState appState;
    private final DialogService dialogService;

    private Button analyzeButton;
    private Button dryRunButton;
    private Button applyButton;
    private Label statusLabel;
    private TextArea reportArea;
    private CollectionMaintenanceReport lastReport;
    private final AtomicLong requestGeneration = new AtomicLong();
    private String displayedCollectionId;

    void attach(Button analyzeButton, Button dryRunButton, Button applyButton,
                Label statusLabel, TextArea reportArea) {
        this.analyzeButton = analyzeButton;
        this.dryRunButton = dryRunButton;
        this.applyButton = applyButton;
        this.statusLabel = statusLabel;
        this.reportArea = reportArea;
    }

    void show(CollectionDto collection) {
        displayedCollectionId = collection == null ? null : collection.getId();
        requestGeneration.incrementAndGet();
        appState.getStatusBar().setProgressVisible(false);
        lastReport = null;
        if (reportArea != null) reportArea.clear();
        if (statusLabel != null) {
            statusLabel.setText(collection != null && collection.isActive()
                    ? UiText.text("ui.fxml.collection_workspace.analysis_has_not_been_run_yet")
                    : UiText.text("ui.java.collection_maintenance_panel_coordinator.daf0e5d891"));
        }
        updateButtons(false, collection);
    }

    void analyze(CollectionDto collection) {
        if (!requireActive(collection)) return;
        String collectionId = collection.getId();
        long requestId = requestGeneration.incrementAndGet();
        setBusy(true, UiText.text("ui.java.collection_maintenance_panel_coordinator.b6c1d41dc9"), collection);
        maintenanceUseCase.analyze(collectionId)
                .whenComplete((report, error) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrent(collectionId, requestId)) return;
                    setBusy(false, null, collection);
                    if (error != null) {
                        dialogService.showError(UiText.text("ui.java.collection_maintenance_panel_coordinator.ac5b03f144"), UiExceptionSupport.message(error));
                        return;
                    }
                    lastReport = report;
                    renderReport(report);
                    updateButtons(false, collection);
                }));
    }

    void dryRun(CollectionDto collection) {
        if (!requireActive(collection) || lastReport == null) return;
        Set<String> ids = repairableIssueIds(lastReport);
        String collectionId = collection.getId();
        long requestId = requestGeneration.incrementAndGet();
        setBusy(true, UiText.text("ui.java.collection_maintenance_panel_coordinator.1dfb6496e0"), collection);
        maintenanceUseCase.dryRun(collectionId, ids)
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrent(collectionId, requestId)) return;
                    setBusy(false, null, collection);
                    if (error != null) {
                        dialogService.showError(UiText.text("ui.fxml.collection_workspace.dry_run"), UiExceptionSupport.message(error));
                    } else if (statusLabel != null) {
                        statusLabel.setText(UiText.text("ui.java.collection_maintenance_panel_coordinator.5b67f1eb0e") + result.requested()
                                + UiText.text("ui.java.collection_maintenance_panel_coordinator.e9c2512251") + result.skipped() + UiText.text("ui.java.collection_maintenance_panel_coordinator.99268a6b35"));
                    }
                }));
    }

    void apply(CollectionDto collection) {
        if (!requireActive(collection) || lastReport == null) return;
        Set<String> ids = repairableIssueIds(lastReport);
        if (ids.isEmpty()) return;

        boolean confirmed = dialogService.showConfirmation(
                UiText.text("ui.java.collection_maintenance_panel_coordinator.cfd1080621"),
                UiText.text("ui.java.collection_maintenance_panel_coordinator.9012bae4ec") + ids.size() + UiText.text("ui.java.collection_maintenance_panel_coordinator.4248f61cbe"),
                UiText.text("ui.java.collection_maintenance_panel_coordinator.7ade82ca4a")
                        + UiText.text("ui.java.collection_maintenance_panel_coordinator.0a876f7894")
                        + UiText.text("ui.java.collection_maintenance_panel_coordinator.538688f1dc"));
        if (!confirmed) return;

        String collectionId = collection.getId();
        long requestId = requestGeneration.incrementAndGet();
        setBusy(true, UiText.text("ui.java.collection_maintenance_panel_coordinator.392f61c6a7"), collection);
        maintenanceUseCase.apply(collectionId, ids)
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrent(collectionId, requestId)) return;
                    setBusy(false, null, collection);
                    if (error != null) {
                        dialogService.showError(UiText.text("ui.java.collection_maintenance_panel_coordinator.ac5b03f144"), UiExceptionSupport.message(error));
                        return;
                    }
                    lastReport = result.after();
                    renderReport(result.after());
                    if (statusLabel != null) {
                        statusLabel.setText(UiText.text("ui.java.collection_maintenance_panel_coordinator.251fedd09e") + result.applied()
                                + UiText.text("ui.java.collection_maintenance_panel_coordinator.e9c2512251") + result.skipped() + UiText.text("ui.java.collection_maintenance_panel_coordinator.43a3ceb744") + result.backupFile());
                    }
                    updateButtons(false, collection);
                    dialogService.showInfo(UiText.text("ui.java.collection_maintenance_panel_coordinator.a247242d88"), UiText.text("ui.java.collection_maintenance_panel_coordinator.e75733236d") + result.backupFile());
                }));
    }

    private boolean requireActive(CollectionDto collection) {
        if (collection == null || !collection.isActive()) {
            dialogService.showWarning(UiText.text("ui.java.collection_maintenance_panel_coordinator.ac5b03f144"), UiText.text("ui.java.collection_maintenance_panel_coordinator.87523e3451"));
            return false;
        }
        return true;
    }

    private Set<String> repairableIssueIds(CollectionMaintenanceReport report) {
        return report.issues().stream()
                .filter(issue -> issue.repairable())
                .map(issue -> issue.issueId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void setBusy(boolean busy, String text, CollectionDto collection) {
        updateButtons(busy, collection);
        if (statusLabel != null && text != null) statusLabel.setText(text);
        appState.getStatusBar().setProgressVisible(busy);
        if (busy) appState.getStatusBar().setProgress(-1);
    }

    private void updateButtons(boolean busy, CollectionDto collection) {
        boolean active = collection != null && collection.isActive();
        boolean canRepair = lastReport != null && lastReport.repairableSamples() > 0;
        if (analyzeButton != null) analyzeButton.setDisable(busy || !active);
        if (dryRunButton != null) dryRunButton.setDisable(busy || !active || !canRepair);
        if (applyButton != null) applyButton.setDisable(busy || !active || !canRepair);
    }

    private boolean isCurrent(String collectionId, long requestId) {
        return requestId == requestGeneration.get() && Objects.equals(displayedCollectionId, collectionId);
    }

    private void renderReport(CollectionMaintenanceReport report) {
        if (statusLabel != null) {
            statusLabel.setText(report.hasIssues()
                    ? UiText.text("ui.java.collection_maintenance_panel_coordinator.6571b40cbe") + report.totalIssues()
                    : UiText.text("ui.java.collection_maintenance_panel_coordinator.d3eee4d594"));
        }
        if (reportArea == null) return;

        StringBuilder sb = new StringBuilder();
        String integrityStatus;
        if (report.databaseIntegrityOk()) {
            integrityStatus = UiText.text("common.status.ok");
        } else {
            integrityStatus = UiText.text("ui.collection.maintenance.database.problem");
            if (report.databaseIntegrityMessage() != null && !report.databaseIntegrityMessage().isBlank()) {
                log.warn("Collection maintenance database integrity detail for {}: {}",
                        report.collectionId(), report.databaseIntegrityMessage());
            }
        }
        sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.49b40f5748")).append(integrityStatus).append('\n');
        sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.9b3e14e0eb")).append(report.scannedBooks()).append('\n');
        sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.164d61ea28")).append(report.scannedFiles()).append('\n');
        sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.fa37f7ca6a")).append(report.missingFiles()).append('\n');
        sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.4e4a0c8c60")).append(report.invalidArchiveReferences()).append('\n');
        sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.687f3ad558")).append(report.orphanFiles()).append(UiText.text("ui.java.collection_maintenance_panel_coordinator.b7d3162da7"));
        sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.f5b222790b")).append(report.orphanedAuthors()).append('\n');
        sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.71e1a0589c")).append(report.orphanedGenres()).append('\n');
        sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.ef235ba587")).append(report.duplicateBooks()).append('\n');
        if (report.samplesTruncated()) {
            sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.38ae683324"));
        }
        sb.append(UiText.text("ui.collection.maintenance.preview_separator"));
        report.issues().stream().limit(200).forEach(issue -> {
            if (issue.technicalDetail() != null && !issue.technicalDetail().isBlank()) {
                log.warn("Collection maintenance issue {} [{}] technical detail: {}",
                        issue.issueId(), issue.type(), issue.technicalDetail());
            }
            String message = UiText.format(issue.messageKey(), issue.messageArguments().toArray());
            sb.append(issue.repairable()
                            ? UiText.text("ui.java.collection_maintenance_panel_coordinator.3ffffed540")
                            : UiText.text("ui.java.collection_maintenance_panel_coordinator.850ed2442b"))
                    .append(message)
                    .append('\n');
        });
        if (report.issues().size() > 200) {
            sb.append(UiText.text("ui.java.collection_maintenance_panel_coordinator.48df6f8a2d")).append(report.issues().size() - 200).append(UiText.text("ui.java.collection_maintenance_panel_coordinator.2ea39cd98d"));
        }
        reportArea.setText(sb.toString());
    }


}
