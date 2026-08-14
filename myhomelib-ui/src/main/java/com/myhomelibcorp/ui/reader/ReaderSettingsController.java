package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.reader.service.ReaderScheduler;
import com.myhomelibcorp.reader.service.ReaderSettingsService;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.myhomelibcorp.reader.session.ReaderSessionManager;
import com.myhomelibcorp.ui.service.DialogService;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class ReaderSettingsController {

    private final ReaderSettingsService settingsService;
    private final ReaderSessionManager sessionManager;
    private final DialogService dialogService;
    private final ReaderScheduler scheduler;

    @FXML private ComboBox<String> fontFamilyCombo;
    @FXML private Slider fontSizeSlider;
    @FXML private Slider lineSpacingSlider;
    @FXML private CheckBox hyphenationCheck;
    @FXML private CheckBox autoScrollCheck;
    @FXML private ComboBox<String> themeCombo;
    @FXML private TextArea customCssArea;
    @FXML private Slider marginTopSlider;
    @FXML private Slider marginBottomSlider;
    @FXML private Slider marginLeftSlider;
    @FXML private Slider marginRightSlider;
    @FXML private Slider firstLineIndentSlider;

    // Нові елементи для ширини тексту та режиму сторінок
    @FXML private ComboBox<String> widthModeCombo;
    @FXML private CheckBox pageModeCheck;

    private Runnable onSaveCallback;

    @FXML
    public void initialize() {
        // Шрифти
        fontFamilyCombo.getItems().addAll(
                "Georgia",
                "Times New Roman",
                "Arial",
                "Helvetica",
                "Verdana",
                "Palatino",
                "Book Antiqua",
                "Courier New",
                "Tahoma",
                "Trebuchet MS"
        );
        fontFamilyCombo.setValue("Georgia");

        // Теми
        themeCombo.getItems().addAll("light", "sepia", "dark", "amoled");
        themeCombo.setValue("light");

        // Режими ширини тексту
        widthModeCombo.getItems().addAll(
                "narrow",    // Вузький
                "medium",    // Середній
                "wide",      // Широкий
                "full"       // На весь екран
        );
        widthModeCombo.setValue("medium");

        // Відображення назв режимів
        widthModeCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(switch (item) {
                        case "narrow" -> "Вузький";
                        case "medium" -> "Середній";
                        case "wide" -> "Широкий";
                        case "full" -> "На весь екран";
                        default -> item;
                    });
                }
            }
        });
        widthModeCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("Середній");
                } else {
                    setText(switch (item) {
                        case "narrow" -> "Вузький";
                        case "medium" -> "Середній";
                        case "wide" -> "Широкий";
                        case "full" -> "На весь екран";
                        default -> item;
                    });
                }
            }
        });

        // Слухачі для автоматичного застосування змін
        widthModeCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null && !val.equals(old)) {
                applyCurrentSettings();
            }
        });

        pageModeCheck.selectedProperty().addListener((obs, old, val) -> {
            applyCurrentSettings();
        });

        // Завантажуємо налаштування
        loadSettings();

        // Додаємо слухачі для інших елементів
        fontFamilyCombo.valueProperty().addListener((obs, old, val) -> applyCurrentSettings());
        fontSizeSlider.valueProperty().addListener((obs, old, val) -> applyCurrentSettings());
        lineSpacingSlider.valueProperty().addListener((obs, old, val) -> applyCurrentSettings());
        hyphenationCheck.selectedProperty().addListener((obs, old, val) -> applyCurrentSettings());
        autoScrollCheck.selectedProperty().addListener((obs, old, val) -> {
            // Автоскрол застосовується при збереженні
        });
        themeCombo.valueProperty().addListener((obs, old, val) -> applyCurrentSettings());
        marginTopSlider.valueProperty().addListener((obs, old, val) -> applyCurrentSettings());
        marginBottomSlider.valueProperty().addListener((obs, old, val) -> applyCurrentSettings());
        marginLeftSlider.valueProperty().addListener((obs, old, val) -> applyCurrentSettings());
        marginRightSlider.valueProperty().addListener((obs, old, val) -> applyCurrentSettings());
        firstLineIndentSlider.valueProperty().addListener((obs, old, val) -> applyCurrentSettings());
    }

    public void setOnSaveCallback(Runnable callback) {
        this.onSaveCallback = callback;
    }

    private void loadSettings() {
        ReaderSettings settings = settingsService.getSettings();
        fontFamilyCombo.setValue(settings.getFontFamily());
        fontSizeSlider.setValue(settings.getFontSize());
        lineSpacingSlider.setValue(settings.getLineSpacing());
        hyphenationCheck.setSelected(settings.isHyphenation());
        autoScrollCheck.setSelected(settings.isAutoScroll());
        themeCombo.setValue(settings.getTheme());
        customCssArea.setText(settings.getCustomCss());
        marginTopSlider.setValue(settings.getMarginTop());
        marginBottomSlider.setValue(settings.getMarginBottom());
        marginLeftSlider.setValue(settings.getMarginLeft());
        marginRightSlider.setValue(settings.getMarginRight());
        firstLineIndentSlider.setValue(settings.getFirstLineIndent());

        // Нові налаштування
        widthModeCombo.setValue(settings.getWidthMode() != null ? settings.getWidthMode() : "medium");
        pageModeCheck.setSelected(settings.isPageMode());
    }

    private void applyCurrentSettings() {
        // Застосовуємо налаштування до поточної книги без збереження
        ReaderSession session = sessionManager.getCurrentSession();
        if (session != null && session.isActive()) {
            // Оновлюємо тимчасові налаштування
            ReaderSettings tempSettings = settingsService.getSettings();

            // Копіюємо поточні значення в тимчасові налаштування
            tempSettings.setFontFamily(fontFamilyCombo.getValue());
            tempSettings.setFontSize(fontSizeSlider.getValue());
            tempSettings.setLineSpacing(lineSpacingSlider.getValue());
            tempSettings.setHyphenation(hyphenationCheck.isSelected());
            tempSettings.setTheme(themeCombo.getValue());
            tempSettings.setCustomCss(customCssArea.getText());
            tempSettings.setMarginTop(marginTopSlider.getValue());
            tempSettings.setMarginBottom(marginBottomSlider.getValue());
            tempSettings.setMarginLeft(marginLeftSlider.getValue());
            tempSettings.setMarginRight(marginRightSlider.getValue());
            tempSettings.setFirstLineIndent(firstLineIndentSlider.getValue());
            tempSettings.setWidthMode(widthModeCombo.getValue());
            tempSettings.setPageMode(pageModeCheck.isSelected());

            // Застосовуємо до книги
            applySettingsToBook(session);
        }
    }

    @FXML
    private void onSave() {
        try {
            ReaderSettings settings = settingsService.getSettings();
            settings.setFontFamily(fontFamilyCombo.getValue());
            settings.setFontSize(fontSizeSlider.getValue());
            settings.setLineSpacing(lineSpacingSlider.getValue());
            settings.setHyphenation(hyphenationCheck.isSelected());
            settings.setAutoScroll(autoScrollCheck.isSelected());
            settings.setTheme(themeCombo.getValue());
            settings.setCustomCss(customCssArea.getText());
            settings.setMarginTop(marginTopSlider.getValue());
            settings.setMarginBottom(marginBottomSlider.getValue());
            settings.setMarginLeft(marginLeftSlider.getValue());
            settings.setMarginRight(marginRightSlider.getValue());
            settings.setFirstLineIndent(firstLineIndentSlider.getValue());

            // Нові налаштування
            settings.setWidthMode(widthModeCombo.getValue());
            settings.setPageMode(pageModeCheck.isSelected());

            settingsService.save();

            ReaderSession session = sessionManager.getCurrentSession();
            if (session != null && session.isActive()) {
                applySettingsToBook(session);

                // Оновлюємо автоскрол якщо потрібно
                if (autoScrollCheck.isSelected()) {
                    // Автоскрол буде застосовано при наступному відкритті книги
                }
            }

            dialogService.showInfo("Успішно", "Налаштування Reader збережено");

            if (onSaveCallback != null) {
                scheduler.runOnFxThread(onSaveCallback);
            }

            closeDialog();

        } catch (Exception e) {
            log.error("Помилка збереження налаштувань Reader", e);
            dialogService.showError("Помилка", "Не вдалося зберегти налаштування: " + e.getMessage());
        }
    }

    private void applySettingsToBook(ReaderSession session) {
        scheduler.runOnFxThread(() -> {
            try {
                String css = settingsService.generateCss();
                String escapedCss = escapeCssForJavaScript(css);

                String script = """
                    (function() {
                        try {
                            var style = document.getElementById('reader-styles');
                            if (!style) {
                                style = document.createElement('style');
                                style.id = 'reader-styles';
                                document.head.appendChild(style);
                            }
                            style.textContent = CSS;
                        } catch(e) {
                            console.error('Failed to apply styles:', e);
                        }
                    })();
                """.replace("CSS", escapedCss);

                session.getWebEngine().executeScript(script);
                log.debug("Settings applied to current book");

            } catch (Exception e) {
                log.warn("Failed to apply settings to current book: {}", e.getMessage());
            }
        });
    }

    private String escapeCssForJavaScript(String css) {
        if (css == null) {
            return "''";
        }

        String escaped = css
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\f", "\\f")
                .replace("\b", "\\b");

        escaped = Pattern.compile("[\\x00-\\x1F\\x7F]").matcher(escaped).replaceAll("");

        return "'" + escaped + "'";
    }

    @FXML
    private void onReset() {
        if (dialogService.showConfirmation("Скинути налаштування",
                "Ви впевнені, що хочете скинути всі налаштування Reader до стандартних?",
                "Цю дію не можна скасувати.")) {

            settingsService.resetToDefaults();
            loadSettings();

            ReaderSession session = sessionManager.getCurrentSession();
            if (session != null && session.isActive()) {
                applySettingsToBook(session);
            }

            dialogService.showInfo("Успішно", "Налаштування Reader скинуто до стандартних");
        }
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    private void closeDialog() {
        Stage stage = (Stage) fontFamilyCombo.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}