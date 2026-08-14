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

@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderSettingsService {

    private final ReaderPreferencesPort preferencesPort;

    private ReaderSettings currentSettings;

    public synchronized ReaderSettings load() {
        if (currentSettings == null) {
            try {
                ReaderPreferences prefs = preferencesPort.loadPreferences();
                currentSettings = ReaderSettings.createDefault();
                currentSettings.fromDomain(prefs);
                log.debug("Reader settings loaded from preferences");
            } catch (Exception e) {
                log.warn("Failed to load preferences, using defaults: {}", e.getMessage());
                currentSettings = ReaderSettings.createDefault();
            }
        }
        return currentSettings;
    }

    public synchronized void save() {
        if (currentSettings == null) {
            return;
        }
        try {
            preferencesPort.savePreferences(currentSettings.toDomain());
            log.debug("Reader settings saved");
        } catch (Exception e) {
            log.error("Failed to save preferences: {}", e.getMessage());
        }
    }

    public ReaderSettings getSettings() {
        return load();
    }

    public void setTheme(String themeName) {
        ReaderSettings s = load();
        s.setTheme(themeName);
        save();
        log.info("Theme changed to: {}", themeName);
    }

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

    public void setFontSize(double size) {
        ReaderSettings s = load();
        s.setFontSize(Math.max(8, Math.min(40, size)));
        save();
    }

    public void setFontFamily(String family) {
        ReaderSettings s = load();
        s.setFontFamily(family);
        save();
    }

    public List<String> getAvailableFonts() {
        return Font.getFamilies().stream()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Генерує CSS для Reader.
     * ВИПРАВЛЕНО: додано екранування для безпечного використання в JavaScript
     */
    public String generateCss() {
        ReaderSettings s = load();
        ReaderTheme theme = ReaderTheme.fromName(s.getTheme());

        StringBuilder css = new StringBuilder();

        css.append("body {\n");
        css.append("    color: ").append(escapeCssValue(theme.getForeground())).append(" !important;\n");
        css.append("    background-color: ").append(escapeCssValue(theme.getBackground())).append(" !important;\n");
        css.append("    font-family: ").append(escapeCssValue(s.getFontFamily())).append(", Georgia, serif;\n");
        css.append("    font-size: ").append(s.getFontSize()).append("px;\n");
        css.append("    line-height: ").append(s.getLineSpacing()).append(";\n");
        css.append("    text-align: ").append(escapeCssValue(s.getAlignment())).append(";\n");
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
        css.append("    color: ").append(escapeCssValue(theme.getForeground())).append(" !important;\n");
        css.append("}\n\n");

        css.append(".chapter-title {\n");
        css.append("    color: ").append(escapeCssValue(theme.getForeground())).append(" !important;\n");
        css.append("}\n\n");

        css.append(".annotation {\n");
        css.append("    background-color: ").append(escapeCssValue(theme.getQuoteBackground())).append(";\n");
        css.append("    color: ").append(escapeCssValue(theme.getSecondaryText())).append(";\n");
        css.append("    padding: 10px;\n");
        css.append("    border-radius: 4px;\n");
        css.append("}\n\n");

        // Решта CSS...
        css.append("/* ===== Поезія ===== */\n");
        css.append(".poem {\n");
        css.append("    margin: 15px 0;\n");
        css.append("    padding: 10px 20px;\n");
        css.append("    font-family: Georgia, serif;\n");
        css.append("    white-space: pre-wrap;\n");
        css.append("    line-height: 1.8;\n");
        css.append("}\n\n");

        // ... інші стилі ...

        // Користувацькі стилі
        if (s.getCustomCss() != null && !s.getCustomCss().isEmpty()) {
            css.append("\n/* User styles */\n");
            css.append(s.getCustomCss());
        }

        return css.toString();
    }

    /**
     * Екранує значення CSS для безпечного використання
     */
    private String escapeCssValue(String value) {
        if (value == null) {
            return "";
        }
        // Видаляємо потенційно небезпечні символи
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    public void resetToDefaults() {
        ReaderPreferences defaults = ReaderPreferences.builder().build();
        preferencesPort.savePreferences(defaults);
        preferencesPort.resetPreferences();
        currentSettings = null;
        load();
        log.info("Reader settings reset to defaults");
    }

    public String getCurrentThemeName() {
        return load().getTheme();
    }
}