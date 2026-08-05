package com.myhomelibcorp.reader.core;

import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.prefs.Preferences;

@Data
@Slf4j
public class ReaderSettings {
    private static final String PREFS_NODE = "myhomelib/reader";

    private String theme = "light";
    private String fontFamily = "Georgia";
    private double fontSize = 18.0;
    private double lineSpacing = 1.6;
    private double paragraphSpacing = 1.5;
    private double firstLineIndent = 1.5;
    private boolean hyphenation = true;
    private String alignment = "justify";
    private double marginTop = 20;
    private double marginBottom = 20;
    private double marginLeft = 30;
    private double marginRight = 30;
    private boolean fullScreen = false;
    private boolean autoScroll = false;
    private int scrollSpeed = 3;
    private boolean showToolbar = true;
    private String customCss = "";

    private static ReaderSettings instance;

    private ReaderSettings() {}

    public static ReaderSettings getInstance() {
        if (instance == null) {
            instance = new ReaderSettings();
            instance.load();
        }
        return instance;
    }

    public void load() {
        Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
        theme = prefs.get("theme", "light");
        fontFamily = prefs.get("fontFamily", "Georgia");
        fontSize = prefs.getDouble("fontSize", 18.0);
        lineSpacing = prefs.getDouble("lineSpacing", 1.6);
        paragraphSpacing = prefs.getDouble("paragraphSpacing", 1.5);
        firstLineIndent = prefs.getDouble("firstLineIndent", 1.5);
        hyphenation = prefs.getBoolean("hyphenation", true);
        alignment = prefs.get("alignment", "justify");
        marginTop = prefs.getDouble("marginTop", 20);
        marginBottom = prefs.getDouble("marginBottom", 20);
        marginLeft = prefs.getDouble("marginLeft", 30);
        marginRight = prefs.getDouble("marginRight", 30);
        fullScreen = prefs.getBoolean("fullScreen", false);
        autoScroll = prefs.getBoolean("autoScroll", false);
        scrollSpeed = prefs.getInt("scrollSpeed", 3);
        showToolbar = prefs.getBoolean("showToolbar", true);
        customCss = prefs.get("customCss", "");
    }

    public void save() {
        Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
        prefs.put("theme", theme);
        prefs.put("fontFamily", fontFamily);
        prefs.putDouble("fontSize", fontSize);
        prefs.putDouble("lineSpacing", lineSpacing);
        prefs.putDouble("paragraphSpacing", paragraphSpacing);
        prefs.putDouble("firstLineIndent", firstLineIndent);
        prefs.putBoolean("hyphenation", hyphenation);
        prefs.put("alignment", alignment);
        prefs.putDouble("marginTop", marginTop);
        prefs.putDouble("marginBottom", marginBottom);
        prefs.putDouble("marginLeft", marginLeft);
        prefs.putDouble("marginRight", marginRight);
        prefs.putBoolean("fullScreen", fullScreen);
        prefs.putBoolean("autoScroll", autoScroll);
        prefs.putInt("scrollSpeed", scrollSpeed);
        prefs.putBoolean("showToolbar", showToolbar);
        prefs.put("customCss", customCss);
    }

    public ReaderPreferences toDomain() {
        return ReaderPreferences.builder()
                .theme(theme)
                .fontFamily(fontFamily)
                .fontSize(fontSize)
                .lineSpacing(lineSpacing)
                .paragraphSpacing(paragraphSpacing)
                .firstLineIndent(firstLineIndent)
                .hyphenation(hyphenation)
                .alignment(alignment)
                .marginTop(marginTop)
                .marginBottom(marginBottom)
                .marginLeft(marginLeft)
                .marginRight(marginRight)
                .fullScreen(fullScreen)
                .autoScroll(autoScroll)
                .scrollSpeed(scrollSpeed)
                .showToolbar(showToolbar)
                .customCss(customCss)
                .build();
    }

    public void fromDomain(ReaderPreferences prefs) {
        this.theme = prefs.getTheme();
        this.fontFamily = prefs.getFontFamily();
        this.fontSize = prefs.getFontSize();
        this.lineSpacing = prefs.getLineSpacing();
        this.paragraphSpacing = prefs.getParagraphSpacing();
        this.firstLineIndent = prefs.getFirstLineIndent();
        this.hyphenation = prefs.isHyphenation();
        this.alignment = prefs.getAlignment();
        this.marginTop = prefs.getMarginTop();
        this.marginBottom = prefs.getMarginBottom();
        this.marginLeft = prefs.getMarginLeft();
        this.marginRight = prefs.getMarginRight();
        this.fullScreen = prefs.isFullScreen();
        this.autoScroll = prefs.isAutoScroll();
        this.scrollSpeed = prefs.getScrollSpeed();
        this.showToolbar = prefs.isShowToolbar();
        this.customCss = prefs.getCustomCss();
    }

    public String toCss() {
        StringBuilder css = new StringBuilder();

        // ВАЖЛИВО: явно встановлюємо колір тексту та фону
        css.append("body {");
        css.append("color: #000000 !important;");
        css.append("background-color: #ffffff !important;");
        css.append("font-family: ").append(fontFamily).append(";");
        css.append("font-size: ").append(fontSize).append("px;");
        css.append("line-height: ").append(lineSpacing).append(";");
        css.append("text-align: ").append(alignment).append(";");
        css.append("margin-top: ").append(marginTop).append("px;");
        css.append("margin-bottom: ").append(marginBottom).append("px;");
        css.append("margin-left: ").append(marginLeft).append("px;");
        css.append("margin-right: ").append(marginRight).append("px;");
        css.append("max-width: 100%;");
        css.append("box-sizing: border-box;");
        css.append("word-wrap: break-word;");
        css.append("overflow-wrap: break-word;");
        css.append("white-space: normal;");
        css.append("overflow-x: hidden;");
        if (hyphenation) {
            css.append("hyphens: auto;");
            css.append("-webkit-hyphens: auto;");
        }
        css.append("}");

        css.append("p {");
        css.append("text-indent: ").append(firstLineIndent).append("em;");
        css.append("margin: 0 0 ").append(paragraphSpacing).append("em 0;");
        css.append("word-wrap: break-word;");
        css.append("overflow-wrap: break-word;");
        css.append("white-space: normal;");
        css.append("color: #000000 !important;");  // Додано
        css.append("}");

        // Теми
        switch (theme) {
            case "light":
                css.append("body { background-color: #ffffff !important; color: #111111 !important; }");
                css.append(".annotation { background-color: #f5f5f5; color: #555; }");
                css.append("blockquote { background-color: #f9f9f9; border-left-color: #ccc; }");
                css.append(".chapter-title { color: #111111 !important; }");
                css.append("p { color: #111111 !important; }");
                break;
            case "sepia":
                css.append("body { background-color: #f5ecd9 !important; color: #331f0a !important; }");
                css.append(".annotation { background-color: #e8dcc8; color: #331f0a; }");
                css.append("blockquote { background-color: #e8dcc8; border-left-color: #8b7355; }");
                css.append(".chapter-title { color: #331f0a !important; }");
                css.append("p { color: #331f0a !important; }");
                break;
            case "dark":
                css.append("body { background-color: #1a1a1a !important; color: #e0e0e0 !important; }");
                css.append(".annotation { background-color: #2a2a2a; color: #ccc; }");
                css.append("blockquote { background-color: #2a2a2a; border-left-color: #555; }");
                css.append(".chapter-title { color: #e0e0e0 !important; }");
                css.append("p { color: #e0e0e0 !important; }");
                break;
            case "amoled":
                css.append("body { background-color: #000000 !important; color: #e0e0e0 !important; }");
                css.append(".annotation { background-color: #111111; color: #ccc; }");
                css.append("blockquote { background-color: #111111; border-left-color: #333; }");
                css.append(".chapter-title { color: #e0e0e0 !important; }");
                css.append("p { color: #e0e0e0 !important; }");
                break;
        }

        if (customCss != null && !customCss.isEmpty()) {
            css.append("\n/* User styles */\n");
            css.append(customCss);
        }

        return css.toString();
    }
}