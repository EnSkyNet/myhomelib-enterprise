package com.myhomelibcorp.ui.reader;

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
    @FXML private ComboBox<String> widthModeCombo;
    @FXML private CheckBox pageModeCheck;

    private Runnable onSaveCallback;
    private boolean isUpdatingUI = false;

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
                "narrow",
                "medium",
                "wide",
                "full"
        );
        widthModeCombo.setValue("medium");

        // Налаштовуємо відображення для ComboBox
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

        // Слухачі з флагом isUpdatingUI
        widthModeCombo.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI && val != null && !val.equals(old)) {
                applyCurrentSettings();
            }
        });

        pageModeCheck.selectedProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI) {
                applyCurrentSettings();
            }
        });

        fontFamilyCombo.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI && val != null && !val.equals(old)) {
                applyCurrentSettings();
            }
        });

        fontSizeSlider.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI) {
                applyCurrentSettings();
            }
        });

        lineSpacingSlider.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI) {
                applyCurrentSettings();
            }
        });

        hyphenationCheck.selectedProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI) {
                applyCurrentSettings();
            }
        });

        themeCombo.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI && val != null && !val.equals(old)) {
                applyCurrentSettings();
            }
        });

        marginTopSlider.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI) {
                applyCurrentSettings();
            }
        });

        marginBottomSlider.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI) {
                applyCurrentSettings();
            }
        });

        marginLeftSlider.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI) {
                applyCurrentSettings();
            }
        });

        marginRightSlider.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI) {
                applyCurrentSettings();
            }
        });

        firstLineIndentSlider.valueProperty().addListener((obs, old, val) -> {
            if (!isUpdatingUI) {
                applyCurrentSettings();
            }
        });

        // Завантажуємо налаштування
        loadSettings();
    }

    public void setOnSaveCallback(Runnable callback) {
        this.onSaveCallback = callback;
    }

    private void loadSettings() {
        isUpdatingUI = true;

        try {
            settingsService.reload();
            ReaderSettings settings = settingsService.getSettings();

            log.info("Loading settings into UI: theme={}, widthMode={}, fontSize={}",
                    settings.getTheme(), settings.getWidthMode(), settings.getFontSize());

            fontFamilyCombo.setValue(settings.getFontFamily());
            fontSizeSlider.setValue(settings.getFontSize());
            lineSpacingSlider.setValue(settings.getLineSpacing());
            hyphenationCheck.setSelected(settings.isHyphenation());
            autoScrollCheck.setSelected(settings.isAutoScroll());
            customCssArea.setText(settings.getCustomCss());
            marginTopSlider.setValue(settings.getMarginTop());
            marginBottomSlider.setValue(settings.getMarginBottom());
            marginLeftSlider.setValue(settings.getMarginLeft());
            marginRightSlider.setValue(settings.getMarginRight());
            firstLineIndentSlider.setValue(settings.getFirstLineIndent());
            pageModeCheck.setSelected(settings.isPageMode());

            String currentTheme = settings.getTheme();
            if (currentTheme != null && !currentTheme.isEmpty()) {
                themeCombo.setValue(currentTheme);
                log.debug("Theme set to: {}", currentTheme);
            }

            String currentWidthMode = settings.getWidthMode();
            if (currentWidthMode != null && !currentWidthMode.isEmpty()) {
                widthModeCombo.setValue(currentWidthMode);
                log.debug("Width mode set to: {}", currentWidthMode);
            }

            log.info("UI loaded: themeCombo={}, widthModeCombo={}",
                    themeCombo.getValue(), widthModeCombo.getValue());

        } finally {
            isUpdatingUI = false;
        }
    }

    private void applyCurrentSettings() {
        if (isUpdatingUI) {
            return;
        }

        ReaderSession session = sessionManager.getCurrentSession();
        if (session != null && session.isActive()) {
            ReaderSettings tempSettings = settingsService.getSettings();

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

            applySettingsToBook(session);
        }
    }

    @FXML
    private void onSave() {
        try {
            isUpdatingUI = true;

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
            settings.setWidthMode(widthModeCombo.getValue());
            settings.setPageMode(pageModeCheck.isSelected());

            settingsService.save();

            ReaderSession session = sessionManager.getCurrentSession();
            if (session != null && session.isActive()) {
                applySettingsToBook(session);
            }

            dialogService.showInfo("Успішно", "Налаштування Reader збережено");

            if (onSaveCallback != null) {
                scheduler.runOnFxThread(onSaveCallback);
            }

            closeDialog();

        } catch (Exception e) {
            log.error("Помилка збереження налаштувань Reader", e);
            dialogService.showError("Помилка", "Не вдалося зберегти налаштування: " + e.getMessage());
        } finally {
            isUpdatingUI = false;
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

        return "'" + css
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\f", "\\f")
                .replace("\b", "\\b")
                .replaceAll("[\\x00-\\x1F\\x7F]", "") + "'";
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