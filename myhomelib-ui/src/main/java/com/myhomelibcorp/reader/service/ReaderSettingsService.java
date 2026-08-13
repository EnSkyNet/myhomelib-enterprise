package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.reader.model.ReaderTheme;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javafx.scene.text.Font;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Єдиний сервіс для роботи з налаштуваннями Reader.
 * Замінює ReaderSettings.getInstance().
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderSettingsService {

    private final ReaderPreferencesPort preferencesPort;

    private ReaderSettings settings;
    private ReaderTheme currentTheme;

    /**
     * Завантажує налаштування з Preferences.
     */
    public synchronized ReaderSettings load() {
        if (settings == null) {
            settings = ReaderSettings.getInstance();
            settings.load();
            currentTheme = ReaderTheme.fromName(settings.getTheme());
        }
        return settings;
    }

    /**
     * Зберігає поточні налаштування.
     */
    public synchronized void save() {
        if (settings == null) {
            return;
        }
        settings.save();
        preferencesPort.savePreferences(settings.toDomain());
        log.info("Reader settings saved");
    }

    /**
     * Отримує поточні налаштування.
     */
    public ReaderSettings getSettings() {
        return load();
    }

    /**
     * Отримує поточну тему.
     */
    public ReaderTheme getTheme() {
        return currentTheme;
    }

    /**
     * Змінює тему.
     */
    public void setTheme(String themeName) {
        ReaderSettings s = load();
        s.setTheme(themeName);
        currentTheme = ReaderTheme.fromName(themeName);
        s.save();
        log.info("Theme changed to: {}", themeName);
    }

    /**
     * Перемикає тему по колу.
     */
    public String toggleTheme() {
        String current = load().getTheme();
        String next = switch (current) {
            case "light" -> "sepia";
            case "sepia" -> "dark";
            case "dark" -> "amoled";
            default -> "light";
        };
        setTheme(next);
        return next;
    }

    /**
     * Змінює розмір шрифту.
     */
    public void setFontSize(double size) {
        ReaderSettings s = load();
        s.setFontSize(Math.max(8, Math.min(40, size)));
        s.save();
    }

    /**
     * Змінює шрифт.
     */
    public void setFontFamily(String family) {
        ReaderSettings s = load();
        s.setFontFamily(family);
        s.save();
    }

    /**
     * Повертає список доступних шрифтів.
     */
    public List<String> getAvailableFonts() {
        return Font.getFamilies().stream()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Генерує CSS для поточних налаштувань.
     */
    public String generateCss() {
        ReaderSettings s = load();
        ReaderTheme theme = currentTheme != null ? currentTheme : ReaderTheme.fromName(s.getTheme());

        StringBuilder css = new StringBuilder();

        css.append("body {\n");
        css.append("    color: ").append(theme.getForeground()).append(" !important;\n");
        css.append("    background-color: ").append(theme.getBackground()).append(" !important;\n");
        css.append("    font-family: ").append(s.getFontFamily()).append(", Georgia, serif;\n");
        css.append("    font-size: ").append(s.getFontSize()).append("px;\n");
        css.append("    line-height: ").append(s.getLineSpacing()).append(";\n");
        css.append("    text-align: ").append(s.getAlignment()).append(";\n");
        css.append("    margin-top: ").append(s.getMarginTop()).append("px;\n");
        css.append("    margin-bottom: ").append(s.getMarginBottom()).append("px;\n");
        css.append("    margin-left: ").append(s.getMarginLeft()).append("px;\n");
        css.append("    margin-right: ").append(s.getMarginRight()).append("px;\n");
        css.append("    max-width: 100%;\n");
        css.append("    box-sizing: border-box;\n");
        css.append("    word-wrap: break-word;\n");
        css.append("    overflow-wrap: break-word;\n");
        css.append("    white-space: normal;\n");
        css.append("    overflow-x: hidden;\n");
        if (s.isHyphenation()) {
            css.append("    hyphens: auto;\n");
            css.append("    -webkit-hyphens: auto;\n");
        }
        css.append("}\n\n");

        css.append("p {\n");
        css.append("    text-indent: ").append(s.getFirstLineIndent()).append("em;\n");
        css.append("    margin: 0 0 ").append(s.getParagraphSpacing()).append("em 0;\n");
        css.append("    word-wrap: break-word;\n");
        css.append("    overflow-wrap: break-word;\n");
        css.append("    white-space: normal;\n");
        css.append("    color: ").append(theme.getForeground()).append(" !important;\n");
        css.append("}\n\n");

        css.append(".chapter-title {\n");
        css.append("    color: ").append(theme.getForeground()).append(" !important;\n");
        css.append("}\n\n");

        css.append(".annotation {\n");
        css.append("    background-color: ").append(theme.getQuoteBackground()).append(";\n");
        css.append("    color: ").append(theme.getSecondaryText()).append(";\n");
        css.append("    padding: 10px;\n");
        css.append("    border-radius: 4px;\n");
        css.append("}\n\n");

        css.append("blockquote {\n");
        css.append("    background-color: ").append(theme.getQuoteBackground()).append(";\n");
        css.append("    border-left: 3px solid ").append(theme.getQuoteBorder()).append(";\n");
        css.append("    padding: 10px 15px;\n");
        css.append("    margin: 10px 0;\n");
        css.append("}\n\n");

        css.append("code, pre {\n");
        css.append("    background-color: ").append(theme.getCodeBackground()).append(";\n");
        css.append("    padding: 2px 4px;\n");
        css.append("    border-radius: 3px;\n");
        css.append("}\n\n");

        css.append(".poem {\n");
        css.append("    white-space: pre-wrap;\n");
        css.append("    font-family: Georgia, serif;\n");
        css.append("    margin: 10px 0;\n");
        css.append("}\n\n");

        // Користувацькі стилі
        if (s.getCustomCss() != null && !s.getCustomCss().isEmpty()) {
            css.append("\n/* User styles */\n");
            css.append(s.getCustomCss());
        }

        return css.toString();
    }

    /**
     * Скидає налаштування до дефолтних.
     */
    public void resetToDefaults() {
        ReaderPreferences defaults = ReaderPreferences.builder().build();
        preferencesPort.savePreferences(defaults);
        preferencesPort.resetPreferences();

        settings = null;
        currentTheme = null;
        load();
        log.info("Reader settings reset to defaults");
    }

    public String getCurrentThemeName() {
        return load().getTheme();
    }
}