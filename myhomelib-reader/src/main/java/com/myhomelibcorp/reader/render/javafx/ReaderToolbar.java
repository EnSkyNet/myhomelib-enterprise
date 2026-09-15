package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderTheme;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToolBar;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public class ReaderToolbar extends ToolBar {

    private final ReaderCanvas canvas;
    private final Function<String, String> text;

    // Навігація
    @Getter private final Button backButton;
    @Getter private final Button prevPageButton;
    @Getter private final Button nextPageButton;
    @Getter private final Button prevChapterButton;
    @Getter private final Button nextChapterButton;

    // Режими
    @Getter private final Button pageModeButton;
    @Getter private final Button autoScrollButton;

    // Зум
    @Getter private final Button zoomOutButton;
    @Getter private final Button zoomInButton;
    @Getter private final Button zoomResetButton;

    // Вигляд
    @Getter private final Button themeButton;
    @Getter private final Button settingsButton;
    @Getter private final Button fullscreenButton;
    @Getter private final Button leftSidebarButton;
    @Getter private final Button rightSidebarButton;

    // Функції
    @Getter private final Button bookmarkButton;
    @Getter private final Button bookmarksButton;
    @Getter private final Button tocButton;
    @Getter private final Button searchButton;
    @Getter private final Button annotationsButton;
    @Getter private final Button bookMapButton;
    @Getter private final Button ttsStartButton;
    @Getter private final Button ttsPauseButton;
    @Getter private final Button ttsStopButton;
    @Getter private final MenuButton moreButton;

    private Consumer<ReaderSettings> onSettingsClick;
    private Runnable onBookmarkClick;
    private Runnable onBookmarksClick;
    private Runnable onTocClick;
    private Runnable onSearchClick;
    private Runnable onAnnotationsClick;
    private Runnable onBookMapClick;
    private Runnable onBackClick;
    private Runnable onToggleLeftSidebarClick;
    private Runnable onToggleRightSidebarClick;
    private Runnable onTtsStartClick;
    private Runnable onTtsPauseClick;
    private Runnable onTtsStopClick;
    private boolean ttsActive;
    private boolean ttsPaused;

    public ReaderToolbar(ReaderCanvas canvas, Function<String, String> text) {
        this.canvas = canvas;
        this.text = text == null ? Function.identity() : text;

        setPadding(new Insets(4, 8, 4, 8));
        setStyle("-fx-background-color: #e0e0e0; -fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");
        setMinHeight(40);
        setPrefHeight(40);

        // ===== НАВІГАЦІЯ =====
        backButton = createButton("←", "ui.reader.toolbar.back");
        prevPageButton = createButton("◀", "ui.reader.toolbar.previous_page");
        nextPageButton = createButton("▶", "ui.reader.toolbar.next_page");
        prevChapterButton = createButton("⇤", "ui.reader.toolbar.previous_chapter");
        nextChapterButton = createButton("⇥", "ui.reader.toolbar.next_chapter");

        // ===== РЕЖИМИ =====
        pageModeButton = createButton("▥", "ui.reader.toolbar.page_mode");
        autoScrollButton = createButton("▶▶", "ui.reader.toolbar.auto_scroll");

        // ===== ЗУМ =====
        zoomOutButton = createButton("🔍−", "ui.reader.toolbar.zoom_out");
        zoomInButton = createButton("🔍+", "ui.reader.toolbar.zoom_in");
        zoomResetButton = createButton("100%", "ui.reader.toolbar.zoom_reset");

        // ===== ВИГЛЯД =====
        themeButton = createButton("🎨", "ui.reader.toolbar.theme");
        settingsButton = createButton("⚙️", "ui.reader.toolbar.settings");
        fullscreenButton = createButton("⛶", "ui.reader.toolbar.fullscreen");
        leftSidebarButton = createButton("◧", "ui.reader.toolbar.left_sidebar");
        rightSidebarButton = createButton("◨", "ui.reader.toolbar.right_sidebar");

        // ===== ФУНКЦІЇ =====
        bookmarkButton = createButton("⭐", "ui.reader.toolbar.add_bookmark");
        bookmarksButton = createButton("🔖", "ui.reader.toolbar.bookmarks");
        tocButton = createButton("📑", "ui.reader.toolbar.toc");
        searchButton = createButton("🔍", "ui.reader.toolbar.search");
        annotationsButton = createButton("📝", "ui.reader.toolbar.annotations");
        bookMapButton = createButton("🗺", "ui.reader.toolbar.bookmap");
        ttsStartButton = createButton("🔊", "ui.reader.toolbar.tts_start");
        ttsPauseButton = createButton("⏯", "ui.reader.toolbar.tts_pause_resume");
        ttsStopButton = createButton("⏹", "ui.reader.toolbar.tts_stop");
        moreButton = createMoreButton();

        // Keep only high-frequency reading actions permanently visible. Secondary
        // display/application actions live in the explicit More menu; ToolBar's native
        // overflow remains a final narrow-window/DPI fallback rather than the primary IA.
        getItems().addAll(
                backButton,
                new Separator(),
                prevChapterButton,
                prevPageButton,
                nextPageButton,
                nextChapterButton,
                new Separator(),
                pageModeButton,
                zoomOutButton,
                zoomInButton,
                new Separator(),
                bookmarkButton,
                tocButton,
                searchButton,
                annotationsButton,
                bookMapButton,
                new Separator(),
                ttsStartButton,
                ttsPauseButton,
                ttsStopButton,
                new Separator(),
                moreButton
        );

        setupActions();
        updateState();

        log.info("✅ ReaderToolbar створено");
    }


    private MenuButton createMoreButton() {
        MenuButton button = new MenuButton("⋮");
        String accessibleName = text.apply("ui.reader.toolbar.more");
        button.setAccessibleText(accessibleName);
        button.setTooltip(new Tooltip(accessibleName));

        button.getItems().addAll(
                proxyItem("ui.reader.toolbar.bookmarks", bookmarksButton),
                new SeparatorMenuItem(),
                proxyItem("ui.reader.toolbar.auto_scroll", autoScrollButton),
                proxyItem("ui.reader.toolbar.zoom_reset", zoomResetButton),
                proxyItem("ui.reader.toolbar.theme", themeButton),
                proxyItem("ui.reader.toolbar.settings", settingsButton),
                proxyItem("ui.reader.toolbar.fullscreen", fullscreenButton),
                new SeparatorMenuItem(),
                proxyItem("ui.reader.toolbar.left_sidebar", leftSidebarButton),
                proxyItem("ui.reader.toolbar.right_sidebar", rightSidebarButton)
        );
        return button;
    }

    private MenuItem proxyItem(String textKey, Button actionButton) {
        MenuItem item = new MenuItem(text.apply(textKey));
        item.disableProperty().bind(actionButton.disableProperty());
        item.setOnAction(event -> actionButton.fire());
        return item;
    }

    private Button createButton(String label, String tooltipKey) {
        Button btn = new Button(label);
        String accessibleName = text.apply(tooltipKey);
        btn.setTooltip(new Tooltip(accessibleName));
        btn.setAccessibleText(accessibleName);
        btn.setStyle("-fx-font-size: 13px; -fx-padding: 2 6 2 6;");
        return btn;
    }

    private void setupActions() {
        // Навігація
        backButton.setOnAction(e -> {
            // Власник workspace має спочатку зберегти позицію, а вже потім
            // закрити книгу. Раніше книга закривалась тут до savePosition().
            if (onBackClick != null) {
                onBackClick.run();
            } else if (canvas.isBookOpen()) {
                canvas.closeBook();
            }
        });

        prevPageButton.setOnAction(e -> canvas.previousPage());
        nextPageButton.setOnAction(e -> canvas.nextPage());
        prevChapterButton.setOnAction(e -> canvas.previousChapter());
        nextChapterButton.setOnAction(e -> canvas.nextChapter());

        // Режими
        pageModeButton.setOnAction(e -> {
            canvas.toggleTwoPageMode();
            updateState();
        });

        autoScrollButton.setOnAction(e -> {
            canvas.toggleAutoScroll();
            updateState();
        });

        // Зум
        zoomOutButton.setOnAction(e -> canvas.zoomOut());
        zoomInButton.setOnAction(e -> canvas.zoomIn());
        zoomResetButton.setOnAction(e -> canvas.resetZoom());

        // Вигляд
        themeButton.setOnAction(e -> {
            canvas.cycleTheme();
            updateState();
        });

        fullscreenButton.setOnAction(e -> toggleFullscreen());

        leftSidebarButton.setOnAction(e -> {
            if (onToggleLeftSidebarClick != null) onToggleLeftSidebarClick.run();
        });
        rightSidebarButton.setOnAction(e -> {
            if (onToggleRightSidebarClick != null) onToggleRightSidebarClick.run();
        });

        settingsButton.setOnAction(e -> {
            if (onSettingsClick != null) {
                onSettingsClick.accept(canvas.getEngine().getSettings());
            }
        });

        // Функції
        bookmarkButton.setOnAction(e -> {
            if (onBookmarkClick != null) {
                onBookmarkClick.run();
            }
        });

        bookmarksButton.setOnAction(e -> {
            if (onBookmarksClick != null) {
                onBookmarksClick.run();
            }
        });

        tocButton.setOnAction(e -> {
            if (onTocClick != null) {
                onTocClick.run();
            }
        });

        searchButton.setOnAction(e -> {
            if (onSearchClick != null) {
                onSearchClick.run();
            }
        });
        annotationsButton.setOnAction(e -> {
            if (onAnnotationsClick != null) onAnnotationsClick.run();
        });
        bookMapButton.setOnAction(e -> {
            if (onBookMapClick != null) onBookMapClick.run();
        });
        ttsStartButton.setOnAction(e -> { if (onTtsStartClick != null) onTtsStartClick.run(); });
        ttsPauseButton.setOnAction(e -> { if (onTtsPauseClick != null) onTtsPauseClick.run(); });
        ttsStopButton.setOnAction(e -> { if (onTtsStopClick != null) onTtsStopClick.run(); });
    }

    private void toggleFullscreen() {
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) {
            stage.setFullScreen(!stage.isFullScreen());
        }
    }

    public void updateState() {
        boolean open = canvas.isBookOpen();
        prevPageButton.setDisable(!open);
        nextPageButton.setDisable(!open);
        prevChapterButton.setDisable(!open);
        nextChapterButton.setDisable(!open);
        bookmarkButton.setDisable(!open);
        bookmarksButton.setDisable(!open);
        tocButton.setDisable(!open);
        searchButton.setDisable(!open);
        annotationsButton.setDisable(!open);
        bookMapButton.setDisable(!open);
        autoScrollButton.setDisable(!open || !canvas.isAutoScrollAllowed());
        ttsStartButton.setDisable(!open || ttsActive);
        ttsPauseButton.setDisable(!open || !ttsActive);
        ttsStopButton.setDisable(!open || !ttsActive);

        pageModeButton.setStyle(canvas.isTwoPageModeEnabled() ?
                "-fx-font-size: 13px; -fx-padding: 2 6 2 6; -fx-background-color: #4CAF50; -fx-text-fill: white;" :
                "-fx-font-size: 13px; -fx-padding: 2 6 2 6;");
        autoScrollButton.setStyle(canvas.isAutoScrollRunning() ?
                "-fx-font-size: 13px; -fx-padding: 2 6 2 6; -fx-background-color: #FF9800; -fx-text-fill: white;" :
                "-fx-font-size: 13px; -fx-padding: 2 6 2 6;");

        String currentTheme = canvas.getEngine().getSettings().themeName();
        String displayTheme = text.apply("ui.reader.theme." + ReaderTheme.fromName(currentTheme).name());
        themeButton.setTooltip(new Tooltip(String.format(java.util.Locale.ROOT, text.apply("ui.reader.toolbar.theme_current"), displayTheme)));
    }

    // ==================== КОЛБЕКИ ====================

    public void setOnSettingsClick(Consumer<ReaderSettings> listener) {
        this.onSettingsClick = listener;
    }

    public void setOnBookmarkClick(Runnable listener) {
        this.onBookmarkClick = listener;
    }

    public void setOnBookmarksClick(Runnable listener) {
        this.onBookmarksClick = listener;
    }

    public void setOnTocClick(Runnable listener) {
        this.onTocClick = listener;
    }

    public void setOnSearchClick(Runnable listener) {
        this.onSearchClick = listener;
    }

    public void setOnAnnotationsClick(Runnable listener) {
        this.onAnnotationsClick = listener;
    }

    public void setOnBookMapClick(Runnable listener) {
        this.onBookMapClick = listener;
    }

    public void setOnBackClick(Runnable listener) {
        this.onBackClick = listener;
    }

    public void setOnToggleLeftSidebarClick(Runnable listener) {
        this.onToggleLeftSidebarClick = listener;
    }

    public void setOnToggleRightSidebarClick(Runnable listener) {
        this.onToggleRightSidebarClick = listener;
    }

    public void refresh() {
        updateState();
    }


    public void setOnTtsStartClick(Runnable listener) { this.onTtsStartClick = listener; }
    public void setOnTtsPauseClick(Runnable listener) { this.onTtsPauseClick = listener; }
    public void setOnTtsStopClick(Runnable listener) { this.onTtsStopClick = listener; }

    public void updateTtsState(boolean active, boolean paused) {
        this.ttsActive = active;
        this.ttsPaused = paused;
        updateState();
        String key = ttsPaused ? "ui.reader.toolbar.tts_resume" : "ui.reader.toolbar.tts_pause";
        String name = text.apply(key);
        ttsPauseButton.setTooltip(new Tooltip(name));
        ttsPauseButton.setAccessibleText(name);
    }
}
