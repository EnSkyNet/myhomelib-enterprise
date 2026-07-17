package com.myhomelibcorp.reader.service;

import javafx.scene.web.WebEngine;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.prefs.Preferences;

@Service
@Scope("prototype")
@Slf4j
public class ReaderThemeManager {

    private static final String PREFS_NODE = "myhomelib/reader";
    private static final String KEY_DARK_THEME = "darkTheme";
    private static final String KEY_ZOOM = "zoomLevel";

    @Getter
    @Setter
    private boolean darkTheme = false;

    @Getter
    @Setter
    private double zoomLevel = 1.0;

    /**
     * Завантажує налаштування з Preferences.
     */
    public void loadSettings() {
        Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
        darkTheme = prefs.getBoolean(KEY_DARK_THEME, false);
        zoomLevel = prefs.getDouble(KEY_ZOOM, 1.0);
    }

    public void saveTheme() {
        Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
        prefs.putBoolean(KEY_DARK_THEME, darkTheme);
    }

    public void saveZoom() {
        Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
        prefs.putDouble(KEY_ZOOM, zoomLevel);
    }

    /**
     * Застосовує тему до WebView через JavaScript.
     */
    public void applyTheme(WebEngine webEngine) {
        String css = darkTheme ?
                "body { background-color: #1a1a1a; color: #e0e0e0; }" +
                ".annotation { background-color: #2a2a2a; color: #ccc; }" +
                "blockquote { background-color: #2a2a2a; border-left-color: #555; }" +
                ".footnotes { border-top-color: #444; }" +
                ".subchapter { border-left-color: #444; }" :
                "body { background-color: #ffffff; color: #000000; }" +
                ".annotation { background-color: #f5f5f5; color: #555; }" +
                "blockquote { background-color: #f9f9f9; border-left-color: #ccc; }" +
                ".footnotes { border-top-color: #ddd; }" +
                ".subchapter { border-left-color: #eee; }";
        webEngine.executeScript(
                "var style = document.getElementById('theme-style');" +
                        "if (!style) {" +
                        "  style = document.createElement('style');" +
                        "  style.id = 'theme-style';" +
                        "  document.head.appendChild(style);" +
                        "}" +
                        "style.innerHTML = '" + css.replace("'", "\\'") + "';"
        );
    }

    public void toggleTheme(WebEngine webEngine) {
        darkTheme = !darkTheme;
        applyTheme(webEngine);
        saveTheme();
    }

    public void setZoomLevel(WebEngine webEngine, double zoom, javafx.scene.web.WebView webView) {
        zoomLevel = zoom;
        webView.setZoom(zoomLevel);
        saveZoom();
    }
}