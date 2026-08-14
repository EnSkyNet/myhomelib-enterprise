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

    public void setWidthMode(String mode) {
        ReaderSettings s = load();
        s.setWidthMode(mode);
        save();
        log.info("Width mode changed to: {}", mode);
    }

    public void togglePageMode() {
        ReaderSettings s = load();
        s.setPageMode(!s.isPageMode());
        save();
        log.info("Page mode toggled to: {}", s.isPageMode());
    }

    public List<String> getAvailableFonts() {
        return Font.getFamilies().stream()
                .sorted()
                .collect(Collectors.toList());
    }

    public String generateCss() {
        ReaderSettings s = load();
        ReaderTheme theme = ReaderTheme.fromName(s.getTheme());

        // Визначаємо ширину тексту
        String maxWidth = switch (s.getWidthMode() != null ? s.getWidthMode() : "medium") {
            case "narrow" -> "600px";
            case "medium" -> "720px";
            case "wide" -> "900px";
            case "full" -> "100%";
            default -> "720px";
        };

        StringBuilder css = new StringBuilder();

        // Стилі для html - завжди резервуємо місце для скролу
        css.append("html {\n");
        css.append("    overflow-y: scroll;\n");
        css.append("    scrollbar-gutter: stable;\n");
        css.append("}\n\n");

        // Стилі для скролу (WebKit)
        css.append("::-webkit-scrollbar {\n");
        css.append("    width: 8px;\n");
        css.append("}\n\n");
        css.append("::-webkit-scrollbar-track {\n");
        css.append("    background: transparent;\n");
        css.append("}\n\n");
        css.append("::-webkit-scrollbar-thumb {\n");
        css.append("    background: rgba(0,0,0,0.2);\n");
        css.append("    border-radius: 4px;\n");
        css.append("}\n\n");
        css.append("::-webkit-scrollbar-thumb:hover {\n");
        css.append("    background: rgba(0,0,0,0.3);\n");
        css.append("}\n\n");

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
        css.append("    max-width: ").append(maxWidth).append(";\n");
        css.append("    padding-right: 0px;\n");
        css.append("    box-sizing: border-box;\n");
        css.append("    margin-left: auto;\n");
        css.append("    margin-right: auto;\n");
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

        css.append(".poem {\n");
        css.append("    margin: 15px 0;\n");
        css.append("    padding: 10px 20px;\n");
        css.append("    font-family: Georgia, serif;\n");
        css.append("    white-space: pre-wrap;\n");
        css.append("    line-height: 1.8;\n");
        css.append("}\n\n");

        css.append(".stanza {\n");
        css.append("    margin: 8px 0;\n");
        css.append("}\n\n");

        css.append(".verse {\n");
        css.append("    padding-left: 10px;\n");
        css.append("    white-space: pre-wrap;\n");
        css.append("    font-family: Georgia, serif;\n");
        css.append("}\n\n");

        css.append(".poem-title {\n");
        css.append("    font-weight: bold;\n");
        css.append("    text-align: center;\n");
        css.append("    margin: 10px 0;\n");
        css.append("    font-size: 1.1em;\n");
        css.append("}\n\n");

        css.append(".poem-author {\n");
        css.append("    text-align: right;\n");
        css.append("    font-style: italic;\n");
        css.append("    margin: 5px 0 10px 0;\n");
        css.append("}\n\n");

        css.append(".epigraph {\n");
        css.append("    margin: 20px 30px;\n");
        css.append("    padding: 10px 20px;\n");
        css.append("    border-left: 3px solid ").append(escapeCssValue(theme.getQuoteBorder())).append(";\n");
        css.append("    font-style: italic;\n");
        css.append("    background-color: ").append(escapeCssValue(theme.getQuoteBackground())).append(";\n");
        css.append("}\n\n");

        css.append(".epigraph-author {\n");
        css.append("    text-align: right;\n");
        css.append("    margin-top: 5px;\n");
        css.append("    font-style: normal;\n");
        css.append("}\n\n");

        css.append("blockquote {\n");
        css.append("    margin: 15px 30px;\n");
        css.append("    padding: 10px 20px;\n");
        css.append("    border-left: 4px solid ").append(escapeCssValue(theme.getQuoteBorder())).append(";\n");
        css.append("    background-color: ").append(escapeCssValue(theme.getQuoteBackground())).append(";\n");
        css.append("    font-style: italic;\n");
        css.append("}\n\n");

        css.append(".subtitle {\n");
        css.append("    font-size: 1.1em;\n");
        css.append("    font-weight: bold;\n");
        css.append("    margin: 15px 0 10px 0;\n");
        css.append("    color: ").append(escapeCssValue(theme.getForeground())).append(";\n");
        css.append("}\n\n");

        css.append(".text-author {\n");
        css.append("    text-align: right;\n");
        css.append("    font-style: italic;\n");
        css.append("    margin: 10px 0;\n");
        css.append("}\n\n");

        css.append("img {\n");
        css.append("    max-width: 100%;\n");
        css.append("    height: auto;\n");
        css.append("    display: block;\n");
        css.append("    margin: 10px auto;\n");
        css.append("    border-radius: 4px;\n");
        css.append("    box-shadow: 0 2px 8px rgba(0,0,0,0.1);\n");
        css.append("}\n\n");

        css.append(".footnote {\n");
        css.append("    font-size: 0.9em;\n");
        css.append("    color: ").append(escapeCssValue(theme.getSecondaryText())).append(";\n");
        css.append("    margin: 10px 0;\n");
        css.append("    padding: 8px 12px;\n");
        css.append("    border-left: 3px solid ").append(escapeCssValue(theme.getQuoteBorder())).append(";\n");
        css.append("}\n\n");

        css.append(".footnote-ref {\n");
        css.append("    color: ").append(escapeCssValue(theme.getLinkColor())).append(";\n");
        css.append("    text-decoration: none;\n");
        css.append("    font-size: 0.8em;\n");
        css.append("    vertical-align: super;\n");
        css.append("}\n\n");

        css.append(".footnote-ref:hover {\n");
        css.append("    text-decoration: underline;\n");
        css.append("}\n\n");

        if (s.getCustomCss() != null && !s.getCustomCss().isEmpty()) {
            css.append("\n/* User styles */\n");
            css.append(s.getCustomCss());
        }

        return css.toString();
    }

    private String escapeCssValue(String value) {
        if (value == null) {
            return "";
        }
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