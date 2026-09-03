package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.usecase.integrity.DataIntegrityChecker;
import com.myhomelibcorp.application.usecase.integrity.IntegrityReport;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrityCheckController {

    private final DataIntegrityChecker integrityChecker;
    private final DialogService dialogService;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;
    private final ApplicationState appState;

    @FXML private TextArea reportArea;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button checkButton;
    @FXML private Button fixButton;
    @FXML private Label statusLabel;
    @FXML private Label issuesSummaryLabel;
    private String lastReportText = "";

    @FXML
    public void initialize() {
        fixButton.setDisable(true);
        progressIndicator.setVisible(false);
        statusLabel.setText("Готово до перевірки");
        reportArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
        reportArea.setText("Натисніть 'Перевірити' для аналізу цілісності бази даних.");
    }

    @FXML
    public void onCheckIntegrity() {
        checkButton.setDisable(true);
        fixButton.setDisable(true);
        progressIndicator.setVisible(true);
        statusLabel.setText("⏳ Перевірка цілісності...");
        reportArea.clear();
        issuesSummaryLabel.setText("");

        var collection = appState.getCurrentLibraryCollection();
        String operationId = operationCenter.start(
                "Перевірка цілісності", collection == null ? "" : collection.getId(),
                OperationStage.INTEGRITY_CHECKS, false);
        executor.submit(integrityChecker::check)
                .whenComplete((report, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        operationCenter.fail(operationId, cause);
                        checkButton.setDisable(false);
                        progressIndicator.setVisible(false);
                        statusLabel.setText("❌ Помилка: " + cause.getMessage());
                        dialogService.showError("Помилка", "Не вдалося виконати перевірку: " + cause.getMessage());
                        log.error("Помилка перевірки цілісності", cause);
                        return;
                    }

                    operationCenter.complete(operationId, report.hasIssues()
                            ? "Виявлено проблем: " + report.problemCount()
                            : "Проблем не виявлено");
                    displayReport(report);
                    checkButton.setDisable(false);
                    progressIndicator.setVisible(false);
                    statusLabel.setText("✅ Перевірку завершено");
                    fixButton.setDisable(!report.hasIssues());
                    if (!report.hasIssues()) {
                        issuesSummaryLabel.setText("✅ ПРОБЛЕМ НЕ ВИЯВЛЕНО");
                        issuesSummaryLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                    } else {
                        issuesSummaryLabel.setText("⚠️ ВИЯВЛЕНО ПРОБЛЕМ: " + formatNumber(report.problemCount()));
                        issuesSummaryLabel.setStyle("-fx-text-fill: orange; -fx-font-weight: bold;");
                    }
                }));
    }

    @FXML
    public void onFixIssues() {
        dialogService.showInfo(
                "Безпечне обслуговування",
                "Автоматичне legacy-виправлення вимкнено. Відкрийте «Колекція → Керування колекціями...» "
                        + "і використайте Maintenance: Analyze → Dry run → Apply. Перед Apply створюється backup.");
    }

    private void displayReport(IntegrityReport report) {
        StringBuilder sb = new StringBuilder();

        // Заголовок
        sb.append("📊 ЗВІТ ПРО ЦІЛІСНІСТЬ БАЗИ ДАНИХ\n");
        sb.append("═".repeat(50)).append("\n\n");

        // Статистика
        sb.append("📈 СТАТИСТИКА:\n");
        sb.append("  ─────────────────────────────────\n");
        sb.append("  📚 Книг без авторів:   ").append(formatNumber(report.booksWithoutAuthor())).append("\n");
        sb.append("  📚 Книг без жанрів:    ").append(formatNumber(report.booksWithoutGenre())).append("\n");
        sb.append("  👤 Авторів без книг:   ").append(formatNumber(report.orphanedAuthors())).append("\n");
        sb.append("  🏷️ Жанрів без книг:    ").append(formatNumber(report.orphanedGenres())).append("\n");
        sb.append("  🔄 Дублікатів книг:    ").append(formatNumber(report.duplicateBooks())).append("\n");
        sb.append("  📚 Серій без книг:     ").append(formatNumber(report.orphanedSeries())).append("\n");
        sb.append("  📕 Книг з невідомою серією: ").append(formatNumber(report.booksWithMissingSeries())).append("\n");
        sb.append("  🔗 Пошкоджених зв’язків: ").append(formatNumber(report.brokenRelations())).append("\n");
        sb.append("  🗄 SQLite:             ").append(report.sqliteIntegrityOk() ? "OK" : "ERROR: " + report.sqliteIntegrityMessage()).append("\n");
        sb.append("  🔎 Lucene:             ").append(report.luceneIntegrityOk() ? "OK" : "ERROR")
                .append(" (").append(formatNumber(report.luceneDocuments())).append(" / ")
                .append(formatNumber(report.catalogBooks())).append(")\n");
        sb.append("  ─────────────────────────────────\n");
        sb.append("  ⚠️ ВСЬОГО ПРОБЛЕМ:     ").append(formatNumber(report.problemCount())).append("\n\n");

        // Детальний список проблем
        if (report.hasIssues()) {
            sb.append("📋 ДЕТАЛЬНИЙ СПИСОК ПРОБЛЕМ:\n");
            sb.append("  ─────────────────────────────────\n");
            int index = 1;
            for (String issue : report.issues()) {
                sb.append("  ").append(String.format("%2d", index)).append(". ").append(issue).append("\n");
                index++;
            }
            sb.append("  ─────────────────────────────────\n\n");

            sb.append("💡 РЕКОМЕНДАЦІЇ:\n");
            if (report.booksWithoutAuthor() > 0) {
                sb.append("  • Видаліть книги без авторів або додайте авторів\n");
            }
            if (report.booksWithoutGenre() > 0) {
                sb.append("  • Додайте жанри до книг без жанрів\n");
            }
            if (report.orphanedAuthors() > 0) {
                sb.append("  • Перевірте авторів без книг у Collection Maintenance\n");
            }
            if (report.orphanedGenres() > 0) {
                sb.append("  • Перевірте жанри без книг у Collection Maintenance\n");
            }
            if (report.duplicateBooks() > 0) {
                sb.append("  • Перевірте детерміновані дублікати у Collection Maintenance\n");
            }
            if (report.orphanedSeries() > 0 || report.booksWithMissingSeries() > 0) {
                sb.append("  • Синхронізуйте довідник серій із каталогом книг\n");
            }
            if (report.brokenRelations() > 0 || !report.sqliteIntegrityOk()) {
                sb.append("  • Не застосовуйте автоматичні зміни до резервного копіювання та аналізу Maintenance\n");
            }
            if (!report.luceneIntegrityOk()) {
                sb.append("  • Перебудуйте Lucene та повторіть перевірку цілісності\n");
            }
            sb.append("\n");
            sb.append("🔧 Для безпечного repair використайте Collection Workspace → Maintenance (з preview/dry-run/backup).");
        } else {
            sb.append("✅ ВСІ ПЕРЕВІРКИ ПРОЙДЕНО УСПІШНО\n");
            sb.append("  База даних не містить проблем цілісності.");
        }

        lastReportText = sb.toString();
        reportArea.setText(lastReportText);
    }

    private String formatNumber(long number) {
        return number < 0 ? "—" : String.format("%,d", number);
    }

    @FXML
    public void onExportReport() {
        if (lastReportText == null || lastReportText.isBlank()) {
            dialogService.showWarning("Експорт", "Спочатку виконайте перевірку цілісності.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Експорт звіту цілісності");
        chooser.setInitialFileName("myhomelib-integrity-report.txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text report", "*.txt"));
        File target = chooser.showSaveDialog(reportArea.getScene() == null ? null : reportArea.getScene().getWindow());
        if (target == null) return;
        try {
            Files.writeString(target.toPath(), lastReportText, StandardCharsets.UTF_8);
            dialogService.showInfo("Експорт", "Звіт збережено: " + target.getAbsolutePath());
        } catch (IOException error) {
            log.error("Не вдалося експортувати integrity report", error);
            dialogService.showError("Експорт", "Не вдалося зберегти звіт: " + error.getMessage());
        }
    }

    @FXML
    public void closeDialog() {
        Stage stage = (Stage) reportArea.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}