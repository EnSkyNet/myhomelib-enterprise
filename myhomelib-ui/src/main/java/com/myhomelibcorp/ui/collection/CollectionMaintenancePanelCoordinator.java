package com.myhomelibcorp.ui.collection;

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
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Owns Analyze -> Preview -> Dry run -> Apply behavior for the collection maintenance panel. */
@Component
@RequiredArgsConstructor
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

    void attach(Button analyzeButton, Button dryRunButton, Button applyButton,
                Label statusLabel, TextArea reportArea) {
        this.analyzeButton = analyzeButton;
        this.dryRunButton = dryRunButton;
        this.applyButton = applyButton;
        this.statusLabel = statusLabel;
        this.reportArea = reportArea;
    }

    void show(CollectionDto collection) {
        lastReport = null;
        if (reportArea != null) reportArea.clear();
        if (statusLabel != null) {
            statusLabel.setText(collection != null && collection.isActive()
                    ? "Аналіз ще не запускався"
                    : "Maintenance доступний тільки для активної колекції");
        }
        updateButtons(false, collection);
    }

    void analyze(CollectionDto collection) {
        if (!requireActive(collection)) return;
        setBusy(true, "Аналіз файлів, архівів і БД...", collection);
        maintenanceUseCase.analyze(collection.getId())
                .whenComplete((report, error) -> UiExecutor.runOnUiThread(() -> {
                    setBusy(false, null, collection);
                    if (error != null) {
                        dialogService.showError("Maintenance", UiExceptionSupport.message(error));
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
        setBusy(true, "Dry run: перевірка плану без змін...", collection);
        maintenanceUseCase.dryRun(collection.getId(), ids)
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    setBusy(false, null, collection);
                    if (error != null) {
                        dialogService.showError("Dry run", UiExceptionSupport.message(error));
                    } else if (statusLabel != null) {
                        statusLabel.setText("Dry run завершено: заплановано " + result.requested()
                                + ", пропущено " + result.skipped() + ". Дані не змінено.");
                    }
                }));
    }

    void apply(CollectionDto collection) {
        if (!requireActive(collection) || lastReport == null) return;
        Set<String> ids = repairableIssueIds(lastReport);
        if (ids.isEmpty()) return;

        boolean confirmed = dialogService.showConfirmation(
                "Застосувати maintenance",
                "Буде застосовано до " + ids.size() + " перевірених проблем",
                "Перед будь-якими змінами автоматично створюється повна резервна копія SQLite. "
                        + "Відсутні/пошкоджені локальні файли лише позначаються як не локальні; "
                        + "фізичні orphan-файли автоматично не видаляються.");
        if (!confirmed) return;

        setBusy(true, "Створення backup і застосування repair...", collection);
        maintenanceUseCase.apply(collection.getId(), ids)
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    setBusy(false, null, collection);
                    if (error != null) {
                        dialogService.showError("Maintenance", UiExceptionSupport.message(error));
                        return;
                    }
                    lastReport = result.after();
                    renderReport(result.after());
                    if (statusLabel != null) {
                        statusLabel.setText("Repair завершено: виправлено " + result.applied()
                                + ", пропущено " + result.skipped() + ". Backup: " + result.backupFile());
                    }
                    updateButtons(false, collection);
                    dialogService.showInfo("Maintenance завершено", "Backup: " + result.backupFile());
                }));
    }

    private boolean requireActive(CollectionDto collection) {
        if (collection == null || !collection.isActive()) {
            dialogService.showWarning("Maintenance", "Аналіз і repair можна виконувати тільки для активної колекції.");
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

    private void renderReport(CollectionMaintenanceReport report) {
        if (statusLabel != null) {
            statusLabel.setText(report.hasIssues()
                    ? "Знайдено проблем: " + report.totalIssues()
                    : "Проблем не знайдено");
        }
        if (reportArea == null) return;

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
        if (report.samplesTruncated()) {
            sb.append("\n⚠ Список нижче семплований; повторіть аналіз після repair для наступної порції.\n");
        }
        sb.append("\n--- Preview ---\n");
        report.issues().stream().limit(200).forEach(issue -> sb.append(issue.repairable() ? "[repair] " : "[report] ")
                .append(issue.description()).append('\n'));
        if (report.issues().size() > 200) {
            sb.append("... ще ").append(report.issues().size() - 200).append(" семплів\n");
        }
        reportArea.setText(sb.toString());
    }


}
