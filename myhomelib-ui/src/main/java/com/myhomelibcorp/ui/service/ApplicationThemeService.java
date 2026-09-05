package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.settings.UiPreferenceService;
import com.myhomelibcorp.shared.util.AppPaths;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Whole-application theme manager. Reader content keeps its independent ReaderTheme. */
@Service
@Slf4j
public class ApplicationThemeService {
    private static final String PREFIX = "ui.theme.";
    private static final String GENERATED_MARKER = "myhomelib-theme-generated-";
    private final UiPreferenceService preferences;
    private final AtomicLong generation = new AtomicLong();
    private ThemeConfig current;
    private volatile String currentGeneratedStylesheet;
    private boolean started;

    public ApplicationThemeService(UiPreferenceService preferences) {
        this.preferences = preferences;
        this.current = load();
    }

    /** Installs automatic styling for the main scene and all dialogs/stages created later. */
    public void start() {
        if (started) return;
        started = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) change.getAddedSubList().forEach(this::observeWindow);
            }
        });
        Window.getWindows().forEach(this::observeWindow);
        apply(current);
    }

    private void observeWindow(Window window) {
        if (window == null) return;
        window.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) applyToScene(newScene);
        });
        if (window.getScene() != null) applyToScene(window.getScene());
    }

    public ThemeConfig current() {
        return current;
    }

    /** Live preview without persisting settings. */
    public void apply(ThemeConfig config) {
        current = config == null ? load() : config.normalized();
        // Force exactly one new generated stylesheet for this theme revision; the first scene creates it
        // and every other scene reuses the same URI.
        currentGeneratedStylesheet = null;
        Window.getWindows().forEach(window -> {
            if (window.getScene() != null) applyToScene(window.getScene());
        });
    }

    public void save(ThemeConfig config) {
        ThemeConfig normalized = (config == null ? defaults() : config).normalized();
        preferences.put(PREFIX + "mode", normalized.mode().name());
        preferences.put(PREFIX + "background", normalized.background());
        preferences.put(PREFIX + "panel", normalized.panel());
        preferences.put(PREFIX + "text", normalized.text());
        preferences.put(PREFIX + "accent", normalized.accent());
        preferences.put(PREFIX + "seriesRow", normalized.seriesRow());
        preferences.put(PREFIX + "bookRow", normalized.bookRow());
        preferences.put(PREFIX + "downloadedRow", normalized.downloadedRow());
        preferences.put(PREFIX + "fontSize", Double.toString(normalized.fontSize()));
        apply(normalized);
    }

    public ThemeConfig load() {
        ThemeMode mode;
        try { mode = ThemeMode.valueOf(preferences.get(PREFIX + "mode", ThemeMode.SYSTEM.name())); }
        catch (RuntimeException ignored) { mode = ThemeMode.SYSTEM; }
        ThemeConfig defaults = defaults();
        return new ThemeConfig(
                mode,
                preferences.get(PREFIX + "background", defaults.background()),
                preferences.get(PREFIX + "panel", defaults.panel()),
                preferences.get(PREFIX + "text", defaults.text()),
                preferences.get(PREFIX + "accent", defaults.accent()),
                preferences.get(PREFIX + "seriesRow", defaults.seriesRow()),
                preferences.get(PREFIX + "bookRow", defaults.bookRow()),
                preferences.get(PREFIX + "downloadedRow", defaults.downloadedRow()),
                parseFontSize(preferences.get(PREFIX + "fontSize", Double.toString(defaults.fontSize())), defaults.fontSize())
        ).normalized();
    }

    public ThemeConfig defaults() {
        return lightPreset(ThemeMode.SYSTEM);
    }

    public ThemeConfig customDefaults() {
        return lightPreset(ThemeMode.CUSTOM);
    }

    /** Cycles the application chrome through visible presets and persists the result. */
    public ThemeConfig cyclePreset() {
        ThemeMode next = switch (current.mode()) {
            case LIGHT -> ThemeMode.DARK;
            case DARK -> ThemeMode.AMOLED;
            case AMOLED -> ThemeMode.LIGHT;
            case SYSTEM -> systemDarkHint() ? ThemeMode.AMOLED : ThemeMode.DARK;
            case CUSTOM -> ThemeMode.LIGHT;
        };
        ThemeConfig preset = switch (next) {
            case LIGHT -> lightPreset(ThemeMode.LIGHT);
            case DARK -> darkPreset(ThemeMode.DARK);
            case AMOLED -> amoledPreset(ThemeMode.AMOLED);
            case SYSTEM -> defaults();
            case CUSTOM -> customDefaults();
        };
        save(preset);
        return current;
    }

    private void applyToScene(Scene scene) {
        if (scene == null) return;
        try {
            String base = getClass().getResource("/css/app-theme-base.css").toExternalForm();
            String generated = currentGeneratedStylesheet;
            if (generated == null || generated.isBlank()) {
                generated = writeGeneratedCss(effective(current)).toUri().toString();
                currentGeneratedStylesheet = generated;
            }
            scene.getStylesheets().removeIf(css -> css.contains(GENERATED_MARKER) || css.endsWith("/css/app-theme-base.css"));
            scene.getStylesheets().add(base);
            scene.getStylesheets().add(generated);
        } catch (Exception error) {
            log.warn("Не вдалося застосувати тему програми", error);
        }
    }

    ThemeConfig effective(ThemeConfig config) {
        ThemeConfig source = config == null ? defaults() : config.normalized();
        return switch (source.mode()) {
            case LIGHT -> lightPreset(ThemeMode.LIGHT);
            case DARK -> darkPreset(ThemeMode.DARK);
            case AMOLED -> amoledPreset(ThemeMode.AMOLED);
            case SYSTEM -> systemDarkHint() ? darkPreset(ThemeMode.SYSTEM) : lightPreset(ThemeMode.SYSTEM);
            case CUSTOM -> source;
        };
    }

    private Path writeGeneratedCss(ThemeConfig config) throws IOException {
        Files.createDirectories(AppPaths.cacheDir());
        long id = generation.incrementAndGet();
        Path file = AppPaths.cacheDir().resolve(GENERATED_MARKER + id + ".css");
        String css = String.format(Locale.ROOT, """
                .root {
                    -mhl-background: %s;
                    -mhl-panel: %s;
                    -mhl-text: %s;
                    -mhl-muted-text: %s;
                    -mhl-accent: %s;
                    -mhl-accent-secondary: %s;
                    -mhl-border: %s;
                    -mhl-series-row: %s;
                    -mhl-book-row: %s;
                    -mhl-downloaded-row: %s;
                    -mhl-success: %s;
                    -mhl-warning: %s;
                    -mhl-danger: %s;
                    -mhl-on-accent: %s;
                    -mhl-warning-bg: %s;
                    -mhl-warning-text: %s;
                    -mhl-code-background: %s;
                    -mhl-code-text: %s;
                    -fx-base: %s;
                    -fx-background: %s;
                    -fx-control-inner-background: %s;
                    -fx-text-background-color: %s;
                    -fx-text-base-color: %s;
                    -fx-accent: %s;
                    -fx-focus-color: %s;
                    -fx-font-size: %.1fpx;
                }
                """,
                config.background(), config.panel(), config.text(), muted(config), config.accent(), secondary(config),
                border(config), config.seriesRow(), config.bookRow(), config.downloadedRow(),
                success(config), warning(config), danger(config), onAccent(config), warningBg(config), warningText(config),
                codeBackground(config), codeText(config),
                config.panel(), config.background(), config.panel(), config.text(), config.text(), config.accent(), config.accent(),
                config.fontSize());
        Files.writeString(file, css, StandardCharsets.UTF_8);
        cleanupOldGenerated();
        return file;
    }

    /** Keep a small rolling cache so already-open scenes never reference a stylesheet deleted mid-session. */
    private void cleanupOldGenerated() {
        try (var paths = Files.list(AppPaths.cacheDir())) {
            var generated = paths
                    .filter(path -> path.getFileName().toString().startsWith(GENERATED_MARKER))
                    .sorted((left, right) -> Long.compare(lastModified(right), lastModified(left)))
                    .toList();
            for (int i = 8; i < generated.size(); i++) {
                try { Files.deleteIfExists(generated.get(i)); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }

    private ThemeConfig lightPreset(ThemeMode mode) {
        return new ThemeConfig(mode, "#f8f9fa", "#ffffff", "#202124", "#1a73e8",
                "#e8f0fe", "#ffffff", "#e6f4ea", 13.0);
    }

    private ThemeConfig darkPreset(ThemeMode mode) {
        return new ThemeConfig(mode, "#202124", "#2b2d31", "#e8eaed", "#8ab4f8",
                "#303b4f", "#25272b", "#23442e", 13.0);
    }

    private ThemeConfig amoledPreset(ThemeMode mode) {
        return new ThemeConfig(mode, "#000000", "#050505", "#f2f2f2", "#64b5f6",
                "#0b1220", "#000000", "#06130a", 13.0);
    }

    private boolean systemDarkHint() {
        String forced = System.getProperty("myhomelib.theme.systemDark");
        if (forced != null) return Boolean.parseBoolean(forced);
        String gtk = System.getenv("GTK_THEME");
        if (gtk != null && gtk.toLowerCase(Locale.ROOT).contains("dark")) return true;
        String colorFgBg = System.getenv("COLORFGBG");
        if (colorFgBg != null) {
            String[] parts = colorFgBg.split(";");
            try { return Integer.parseInt(parts[parts.length - 1]) < 8; } catch (Exception ignored) { }
        }
        String apple = System.getProperty("apple.awt.application.appearance", "");
        return apple.toLowerCase(Locale.ROOT).contains("dark");
    }

    private double parseFontSize(String value, double fallback) {
        try { return Math.max(9.0, Math.min(24.0, Double.parseDouble(value))); }
        catch (Exception ignored) { return fallback; }
    }

    private String muted(ThemeConfig c) { return isDark(c.background()) ? "#aeb4bc" : "#6b7280"; }
    private String secondary(ThemeConfig c) { return isDark(c.background()) ? "#c58af9" : "#7b1fa2"; }
    private String border(ThemeConfig c) { return isDark(c.background()) ? "#4a4d52" : "#d5d9df"; }
    private String success(ThemeConfig c) { return isDark(c.background()) ? "#81c995" : "#2e7d32"; }
    private String warning(ThemeConfig c) { return isDark(c.background()) ? "#fdd663" : "#ed8b00"; }
    private String danger(ThemeConfig c) { return isDark(c.background()) ? "#f28b82" : "#c62828"; }
    private String onAccent(ThemeConfig c) { return isDark(c.accent()) ? "#ffffff" : "#111111"; }
    private String warningBg(ThemeConfig c) { return isDark(c.background()) ? "#4b3b12" : "#fff3cd"; }
    private String warningText(ThemeConfig c) { return isDark(c.background()) ? "#fdd663" : "#7a4f00"; }
    private String codeBackground(ThemeConfig c) { return isDark(c.background()) ? "#151515" : "#1e1e1e"; }
    private String codeText(ThemeConfig c) { return "#d4d4d4"; }

    private boolean isDark(String color) {
        try {
            String hex = color.replace("#", "");
            if (hex.length() == 3) hex = "" + hex.charAt(0)+hex.charAt(0)+hex.charAt(1)+hex.charAt(1)+hex.charAt(2)+hex.charAt(2);
            int rgb = Integer.parseInt(hex.substring(0, 6), 16);
            int r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;
            return (0.2126*r + 0.7152*g + 0.0722*b) < 128;
        } catch (Exception ignored) { return false; }
    }

    public enum ThemeMode { SYSTEM, LIGHT, DARK, AMOLED, CUSTOM }

    public record ThemeConfig(ThemeMode mode, String background, String panel, String text, String accent,
                              String seriesRow, String bookRow, String downloadedRow, double fontSize) {
        ThemeConfig normalized() {
            ThemeMode safeMode = mode == null ? ThemeMode.SYSTEM : mode;
            return new ThemeConfig(safeMode, color(background, "#f8f9fa"), color(panel, "#ffffff"),
                    color(text, "#202124"), color(accent, "#1a73e8"), color(seriesRow, "#e8f0fe"),
                    color(bookRow, "#ffffff"), color(downloadedRow, "#e6f4ea"), Math.max(9, Math.min(24, fontSize)));
        }
        private static String color(String value, String fallback) {
            if (value == null || !value.matches("#[0-9a-fA-F]{6}")) return fallback;
            return value.toLowerCase(Locale.ROOT);
        }
    }
}
