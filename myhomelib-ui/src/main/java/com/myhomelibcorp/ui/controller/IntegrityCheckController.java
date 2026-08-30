package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.usecase.integrity.DataIntegrityChecker;
import com.myhomelibcorp.application.usecase.integrity.IntegrityReport;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrityCheckController {

    private final DataIntegrityChecker integrityChecker;
    private final DialogService dialogService;

    @FXML private TextArea reportArea;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button checkButton;
    @FXML private Button fixButton;
    @FXML private Label statusLabel;
    @FXML private Label issuesSummaryLabel;

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

        new Thread(() -> {
            try {
                IntegrityReport report = integrityChecker.check();
                UiExecutor.runOnUiThread(() -> {
                    displayReport(report);
                    checkButton.setDisable(false);
                    progressIndicator.setVisible(false);
                    statusLabel.setText("✅ Перевірку завершено");

                    boolean hasIssues = report.hasIssues();
                    fixButton.setDisable(true);

                    if (!hasIssues) {
                        issuesSummaryLabel.setText("✅ ПРОБЛЕМ НЕ ВИЯВЛЕНО");
                        issuesSummaryLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                    } else {
                        issuesSummaryLabel.setText("⚠️ ВИЯВЛЕНО " + report.issues().size() + " ПРОБЛЕМ");
                        issuesSummaryLabel.setStyle("-fx-text-fill: orange; -fx-font-weight: bold;");
                    }
                });
            } catch (Exception e) {
                UiExecutor.runOnUiThread(() -> {
                    checkButton.setDisable(false);
                    progressIndicator.setVisible(false);
                    statusLabel.setText("❌ Помилка: " + e.getMessage());
                    dialogService.showError("Помилка", "Не вдалося виконати перевірку: " + e.getMessage());
                });
                log.error("Помилка перевірки цілісності", e);
            }
        }).start();
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
        sb.append("  ─────────────────────────────────\n");
        sb.append("  ⚠️ ВСЬОГО ПРОБЛЕМ:     ").append(formatNumber(report.issues().size())).append("\n\n");

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
            sb.append("\n");
            sb.append("🔧 Для безпечного repair використайте Collection Workspace → Maintenance (з preview/dry-run/backup).");
        } else {
            sb.append("✅ ВСІ ПЕРЕВІРКИ ПРОЙДЕНО УСПІШНО\n");
            sb.append("  База даних не містить проблем цілісності.");
        }

        reportArea.setText(sb.toString());
    }

    private String formatNumber(long number) {
        return String.format("%,d", number);
    }

    @FXML
    public void closeDialog() {
        Stage stage = (Stage) reportArea.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}