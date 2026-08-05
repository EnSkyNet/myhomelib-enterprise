package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.ui.service.DialogService;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderSettingsController {

    private final ReaderPreferencesPort readerPreferencesPort;
    private final DialogService dialogService;

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

    private Runnable onSaveCallback;

    @FXML
    public void initialize() {
        // Шрифти
        fontFamilyCombo.getItems().addAll(
                "Georgia", "Times New Roman", "Arial", "Helvetica", "Verdana", "Palatino", "Book Antiqua"
        );
        fontFamilyCombo.setValue("Georgia");

        // Теми
        themeCombo.getItems().addAll("light", "sepia", "dark", "amoled");
        themeCombo.setValue("light");

        loadSettings();
    }

    public void setOnSaveCallback(Runnable callback) {
        this.onSaveCallback = callback;
    }

    private void loadSettings() {
        ReaderSettings settings = ReaderSettings.getInstance();
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
    }

    @FXML
    private void onSave() {
        try {
            ReaderSettings settings = ReaderSettings.getInstance();
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
            settings.save();

            // Зберігаємо через порт
            ReaderPreferences prefs = settings.toDomain();
            readerPreferencesPort.savePreferences(prefs);

            dialogService.showInfo("Успішно", "Налаштування Reader збережено");

            if (onSaveCallback != null) {
                onSaveCallback.run();
            }

            closeDialog();

        } catch (Exception e) {
            log.error("Помилка збереження налаштувань Reader", e);
            dialogService.showError("Помилка", "Не вдалося зберегти налаштування: " + e.getMessage());
        }
    }

    @FXML
    private void onReset() {
        if (dialogService.showConfirmation("Скинути налаштування",
                "Ви впевнені, що хочете скинути всі налаштування Reader до стандартних?",
                "Цю дію не можна скасувати.")) {
            ReaderPreferences defaultPrefs = ReaderPreferences.builder().build();
            readerPreferencesPort.savePreferences(defaultPrefs);
            readerPreferencesPort.resetPreferences();

            ReaderSettings settings = ReaderSettings.getInstance();
            settings.load();
            loadSettings();

            dialogService.showInfo("Успішно", "Налаштування скинуто до стандартних");
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