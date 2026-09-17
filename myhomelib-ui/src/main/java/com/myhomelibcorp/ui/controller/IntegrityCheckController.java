package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.health.LibraryHealthIssue;
import com.myhomelibcorp.application.health.LibraryHealthIssueType;
import com.myhomelibcorp.application.health.LibraryHealthReport;
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
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private Future<?> contentIndexRebuildTask;

    @FXML
    public void initialize() {
        progressIndicator.setVisible(false);
        statusLabel.setText("Готово до перевірки");
        overallStatusLabel.setText("Стан ще не перевірено");
        configureIssueTable();
        if (contentIndexProgress != null) { contentIndexProgress.setProgress(0); contentIndexProgress.setVisible(false); contentIndexProgress.setManaged(false); }
        if (contentIndexCancelButton != null) { contentIndexCancelButton.setDisable(true); }
        refreshContentIndexHealth();
        detailArea.setText("Натисніть «Оновити стан», щоб перевірити цілісність і хеші та зібрати показники бібліотеки.");
    }

    private void configureIssueTable() {
        severityColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(switch (cell.getValue().severity()) {
            case ERROR -> "Помилка";
            case WARNING -> "Увага";
            case INFO -> "Інформація";
        }));
        categoryColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().title()));
        countColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().count()));
        actionColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().action()));
        issueTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, issue) -> {
            if (issue != null) displayIssueDetail(issue);
        });
        issueTable.setPlaceholder(new Label("Проблем не виявлено"));
    }

    @FXML
    public void onCheckIntegrity() {
        checkButton.setDisable(true);
        progressIndicator.setVisible(true);
        statusLabel.setText("Перевірка локальних файлів, бази SQLite та пошукового індексу...");
        detailArea.setText("Виконується інкрементальна перевірка. Для незмінених файлів використовуються попередні контрольні хеш-значення.");

        var collection = appState.getCurrentLibraryCollection();
        String operationId = operationCenter.start(
                "Перевірка стану бібліотеки", collection == null ? "" : collection.getId(),
                OperationStage.INTEGRITY_CHECKS, false);
        executor.submit(healthService::refresh)
                .whenComplete((report, error) -> UiExecutor.runOnUiThread(() -> {
                    checkButton.setDisable(false);
                    progressIndicator.setVisible(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        operationCenter.fail(operationId, cause);
                        statusLabel.setText("Помилка перевірки: " + cause.getMessage());
                        dialogService.showError("Стан бібліотеки", "Не вдалося оновити стан: " + cause.getMessage());
                        log.error("Library Health refresh failed", cause);
                        return;
                    }

                    lastReport = report;
                    lastReportText = formatExport(report);
                    operationCenter.complete(operationId, report.healthy()
                            ? "Проблем не виявлено"
                            : "Проблем: " + formatNumber(report.totalProblemCount()));
                    displayReport(report);
                    statusLabel.setText("Оновлено: " + DATE_TIME.format(report.generatedAt()));
                }));
    }

    private void displayReport(LibraryHealthReport report) {
        localArtifactsValue.setText(formatNumber(report.localArtifacts()));
        missingValue.setText(formatNumber(report.missingArtifacts()));
        corruptValue.setText(formatNumber(report.corruptArtifacts()));
        changedValue.setText(formatNumber(report.changedArtifacts()));
        duplicatesValue.setText(formatNumber(report.duplicateBooks()));
        metadataValue.setText(formatNumber(report.metadataGaps()));
        indexValue.setText(report.searchIndexFresh() ? "Актуальний" : "Потребує перебудови");
        backupValue.setText(report.latestBackupAt() == null
                ? "Не знайдено"
                : formatAge(report.backupAgeHours()));

        if (report.healthy()) {
            overallStatusLabel.setText("Стан бібліотеки: основні перевірки пройдено");
            overallStatusLabel.setStyle("-fx-text-fill: -mhl-success; -fx-font-weight: bold; -fx-font-size: 15px;");
        } else {
            overallStatusLabel.setText("Стан бібліотеки: проблем — " + formatNumber(report.totalProblemCount()));
            overallStatusLabel.setStyle("-fx-text-fill: -mhl-warning; -fx-font-weight: bold; -fx-font-size: 15px;");
        }

        issueTable.setItems(FXCollections.observableArrayList(report.issues()));
        if (report.issues().isEmpty()) {
            detailArea.setText("Проблем не виявлено.\n\n"
                    + "Перевірка файлів: перевірено " + formatNumber(report.auditInspected())
                    + ", повторно використано без обчислення хешу: " + formatNumber(report.auditReused())
                    + ", прочитано: " + formatBytes(report.auditBytesRead()) + ".");
        } else {
            issueTable.getSelectionModel().selectFirst();
        }
    }

    private void displayIssueDetail(LibraryHealthIssue issue) {
        if (lastReport == null) return;
        StringBuilder text = new StringBuilder();
        text.append(issue.title()).append("\n")
                .append("Кількість: ").append(formatNumber(issue.count())).append("\n\n")
                .append(issue.detail()).append("\n\n")
                .append("Рекомендована дія: ").append(issue.action());

        List<ArtifactIntegrityFinding> matches = artifactFindings(issue.type());
        if (!matches.isEmpty()) {
            text.append("\n\nФайли (деталі перевірки, максимум у поточному звіті):\n");
            for (ArtifactIntegrityFinding finding : matches) {
                text.append("• ").append(finding.bookTitle().isBlank() ? finding.bookId() : finding.bookTitle())
                        .append(" [").append(finding.status()).append("]\n  ")
                        .append(finding.path());
                if (!finding.detail().isBlank()) text.append("\n  ").append(finding.detail());
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
        refreshContentIndexHealth();
        String id = currentCollectionId();
        if (id.isBlank()) return;
        executor.submit(() -> contentIndexMaintenance.health(id))
                .thenAccept(health -> UiExecutor.runOnUiThread(() -> detailArea.setText(formatContentIndexHealth(health))));
    }

    @FXML
    public void onRebuildContentIndex() {
        String id = currentCollectionId();
        if (id.isBlank()) {
            dialogService.showWarning("Повнотекстовий індекс", "Активну бібліотеку не вибрано.");
            return;
        }
        if (!dialogService.showConfirmation("Перебудова повнотекстового індексу",
                "Перебудувати окремий індекс вмісту книг?",
                "Активний індекс залишиться доступним до успішного завершення атомарної перебудови.")) return;

        contentIndexCancel.set(false);
        setContentRebuildRunning(true);
        updateContentProgress(new ContentIndexRebuildProgress(0, 0, "", "starting"));
        String operationTitle = "Перебудова повнотекстового індексу";
        String operationId = operationCenter.start(operationTitle, id, OperationKind.INDEX_REBUILD,
                OperationStage.UPDATING_SEARCH_INDEX, true);
        contentIndexRebuildTask = executor.submit(() -> contentIndexMaintenance.rebuild(
                id, contentIndexCancel::get, progress -> {
                    OperationProgress telemetry = OperationProgress.stage(
                                    operationId, OperationStage.UPDATING_SEARCH_INDEX, true)
                            .withProgress(progress.processedBooks(), progress.totalBooks())
                            .withCurrentItem(progress.currentBook().isBlank() ? progress.phase() : progress.currentBook());
                    operationCenter.accept(operationTitle, id, OperationKind.INDEX_REBUILD, telemetry);
                    UiExecutor.runOnUiThread(() -> updateContentProgress(progress));
                }))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        operationCenter.fail(operationId, UiExceptionSupport.unwrapAsync(error));
                    } else if (result == null) {
                        operationCenter.fail(operationId, "Перебудова завершилась без результату");
                    } else if (result.status() == ContentIndexRebuildResult.Status.COMPLETED) {
                        operationCenter.complete(operationId, "Книг: " + result.processedBooks()
                                + " · проіндексовано файлів: " + result.indexedArtifacts());
                    } else if (result.status() == ContentIndexRebuildResult.Status.CANCELLED) {
                        operationCenter.cancel(operationId, "Перебудову повнотекстового індексу скасовано");
                    } else {
                        operationCenter.fail(operationId, result.message());
                    }
                    UiExecutor.runOnUiThread(() -> {
                        setContentRebuildRunning(false);
                        if (error != null) {
                            Throwable cause = UiExceptionSupport.unwrapAsync(error);
                            dialogService.showError("Повнотекстовий індекс", "Помилка перебудови: " + cause.getMessage());
                            refreshContentIndexHealth();
                            return;
                        }
                        if (result == null) return;
                        displayContentIndexHealth(result.health());
                        if (result.status() == ContentIndexRebuildResult.Status.COMPLETED) {
                            detailArea.setText("Повнотекстовий індекс успішно перебудовано.\n"
                                    + "Книг оброблено: " + formatNumber(result.processedBooks()) + "\n"
                                    + "Проіндексовано файлів: " + formatNumber(result.indexedArtifacts()) + "\n\n"
                                    + formatContentIndexHealth(result.health()));
                        } else if (result.status() == ContentIndexRebuildResult.Status.CANCELLED) {
                            detailArea.setText("Перебудову скасовано. Попередній активний індекс вмісту збережено.\n\n"
                                    + formatContentIndexHealth(result.health()));
                        } else {
                            detailArea.setText("Перебудова завершилась помилкою; попередній активний індекс вмісту збережено.\n"
                                    + result.message() + "\n\n" + formatContentIndexHealth(result.health()));
                        }
                    });
                });
    }

    @FXML
    public void onCancelContentIndexRebuild() {
        contentIndexCancel.set(true);
        if (contentIndexCancelButton != null) contentIndexCancelButton.setDisable(true);
        if (contentIndexDetailLabel != null) contentIndexDetailLabel.setText("Скасування…");
    }

    private void refreshContentIndexHealth() {
        String id = currentCollectionId();
        if (id.isBlank()) {
            if (contentIndexValue != null) contentIndexValue.setText("—");
            if (contentIndexDetailLabel != null) contentIndexDetailLabel.setText("Немає активної бібліотеки");
            return;
        }
        executor.submit(() -> contentIndexMaintenance.health(id))
                .thenAccept(health -> UiExecutor.runOnUiThread(() -> displayContentIndexHealth(health)))
                .exceptionally(error -> {
                    UiExecutor.runOnUiThread(() -> {
                        if (contentIndexValue != null) contentIndexValue.setText("Помилка");
                        if (contentIndexDetailLabel != null) contentIndexDetailLabel.setText(UiExceptionSupport.unwrapAsync(error).getMessage());
                    });
                    return null;
                });
    }

    private void displayContentIndexHealth(ContentIndexHealth health) {
        if (health == null) return;
        if (contentIndexValue != null) contentIndexValue.setText(health.compatible() ? "OK" : "Перебудувати");
        if (contentIndexDetailLabel != null) contentIndexDetailLabel.setText(
                "v" + health.schemaVersion() + " · документів: " + formatNumber(health.documentCount()) + " · " + formatBytes(health.sizeBytes()));
    }

    private String formatContentIndexHealth(ContentIndexHealth health) {
        if (health == null) return "Повнотекстовий індекс: стан недоступний";
        return "Повнотекстовий індекс вмісту\n"
                + "Колекція: " + health.collectionId() + "\n"
                + "Версія схеми: " + health.schemaVersion() + "\n"
                + "Документів: " + formatNumber(health.documentCount()) + "\n"
                + "Розмір: " + formatBytes(health.sizeBytes()) + "\n"
                + "Сумісний: " + (health.compatible() ? "ТАК" : "НІ") + "\n"
                + "Стан: " + health.message() + "\n\n"
                + "Індекс метаданих перебудовується окремо через «Інструменти бази даних» → «Перебудувати індекс».";
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
        if (phase == null || phase.isBlank()) return "Обробка";
        return switch (phase.toLowerCase(java.util.Locale.ROOT)) {
            case "starting" -> "Підготовка";
            case "extracting" -> "Видобування тексту";
            case "extracted" -> "Текст опрацьовано";
            case "complete" -> "Завершено";
            default -> phase;
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
            case MISSING_ARTIFACTS -> "Відсутніх локальних файлів не виявлено.";
            case CORRUPT_ARTIFACTS -> "Пошкоджених або нечитабельних локальних файлів не виявлено.";
            case CHANGED_ARTIFACTS -> "Локальних файлів зі зміненим вмістом не виявлено.";
            case DUPLICATES -> "Фізичних дублікатів не виявлено.";
            case METADATA_GAPS -> "Критичних прогалин метаданих не виявлено.";
            case STALE_SEARCH_INDEX -> "Пошуковий індекс актуальний.\n" + lastReport.searchIndexDetail();
            case BACKUP_AGE -> lastReport.latestBackupAt() == null
                    ? "Резервну копію не знайдено."
                    : "Остання резервна копія: " + DATE_TIME.format(lastReport.latestBackupAt())
                    + " (" + formatAge(lastReport.backupAgeHours()) + ").";
            case DATABASE_INTEGRITY -> "Перевірка цілісності SQLite: без помилок.";
        };
    }

    @FXML
    public void onSafeRepairInfo() {
        dialogService.showInfo("Безпечне обслуговування",
                "Перевірка файлів є лише діагностичною і не змінює файли чи базові хеш-значення у book_artifacts. "
                        + "Для виправлень використовуйте «Обслуговування колекції»: «Аналіз» → «Пробний запуск» → «Застосувати»; "
                        + "для застарілого індексу — окрему команду перебудови.");
    }

    @FXML
    public void onExportReport() {
        if (lastReportText == null || lastReportText.isBlank()) {
            dialogService.showWarning("Експорт", "Спочатку оновіть стан бібліотеки.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Експорт звіту про стан бібліотеки");
        chooser.setInitialFileName("myhomelib-library-health.txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Текстовий звіт", "*.txt"));
        File target = chooser.showSaveDialog(detailArea.getScene() == null ? null : detailArea.getScene().getWindow());
        if (target == null) return;
        try {
            Files.writeString(target.toPath(), lastReportText, StandardCharsets.UTF_8);
            dialogService.showInfo("Експорт", "Звіт збережено: " + target.getAbsolutePath());
        } catch (IOException error) {
            log.error("Cannot export Library Health report", error);
            dialogService.showError("Експорт", "Не вдалося зберегти звіт: " + error.getMessage());
        }
    }

    private String formatExport(LibraryHealthReport report) {
        StringBuilder out = new StringBuilder();
        out.append("MyHomeLib — звіт про стан бібліотеки\n")
                .append("Створено: ").append(DATE_TIME.format(report.generatedAt())).append("\n\n")
                .append("Локальних файлів: ").append(report.localArtifacts()).append('\n')
                .append("Відсутніх: ").append(report.missingArtifacts()).append('\n')
                .append("Пошкоджених/нечитабельних: ").append(report.corruptArtifacts()).append('\n')
                .append("Змінених: ").append(report.changedArtifacts()).append('\n')
                .append("Дублікатів: ").append(report.duplicateBooks()).append('\n')
                .append("Прогалин метаданих: ").append(report.metadataGaps()).append('\n')
                .append("База SQLite: ").append(report.databaseHealthy() ? "без помилок" : "помилка").append('\n')
                .append("Пошуковий індекс: ").append(report.searchIndexFresh() ? "актуальний" : "застарілий")
                .append(" (").append(report.searchIndexDetail()).append(")\n")
                .append("Резервна копія: ").append(report.latestBackupAt() == null ? "не знайдено" : DATE_TIME.format(report.latestBackupAt()))
                .append("; вік, год=").append(report.backupAgeHours()).append('\n')
                .append("Перевірка файлів: перевірено=").append(report.auditInspected())
                .append(", повторно використано=").append(report.auditReused())
                .append(", прочитано байтів=").append(report.auditBytesRead()).append("\n\n");

        out.append("Проблеми\n--------\n");
        if (report.issues().isEmpty()) out.append("немає\n");
        for (LibraryHealthIssue issue : report.issues()) {
            out.append(issue.severity()).append(" | ").append(issue.type()).append(" | кількість=")
                    .append(issue.count()).append(" | ").append(issue.title()).append('\n')
                    .append("  ").append(issue.detail()).append('\n')
                    .append("  дія: ").append(issue.action()).append('\n');
        }

        if (!report.artifactFindings().isEmpty()) {
            out.append("\nЗнайдені проблеми файлів\n------------------------\n");
            for (ArtifactIntegrityFinding finding : report.artifactFindings()) {
                out.append(finding.status()).append(" | ").append(finding.artifactId())
                        .append(" | книга=").append(finding.bookId()).append(" | ").append(finding.path()).append('\n');
                if (!finding.detail().isBlank()) out.append("  ").append(finding.detail()).append('\n');
                if (!finding.baselineSha256().isBlank() || !finding.observedSha256().isBlank()) {
                    out.append("  базовий SHA-256=").append(finding.baselineSha256())
                            .append(" поточний SHA-256=").append(finding.observedSha256()).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String formatNumber(long number) {
        return number < 0 ? "—" : String.format(Locale.ROOT, "%,d", number).replace(',', ' ');
    }

    private static String formatAge(long hours) {
        if (hours < 0) return "—";
        long days = hours / 24;
        long remainder = hours % 24;
        return days > 0 ? days + " д " + remainder + " год" : hours + " год";
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
