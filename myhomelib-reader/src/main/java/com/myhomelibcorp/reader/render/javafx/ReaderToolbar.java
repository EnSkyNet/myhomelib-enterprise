package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@Slf4j
public class ReaderToolbar extends HBox {

    private final ReaderCanvas canvas;

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

    // Функції
    @Getter private final Button bookmarkButton;
    @Getter private final Button bookmarksButton;
    @Getter private final Button tocButton;
    @Getter private final Button searchButton;

    private Consumer<ReaderSettings> onSettingsClick;
    private Runnable onBookmarkClick;
    private Runnable onBookmarksClick;
    private Runnable onTocClick;
    private Runnable onSearchClick;
    private Runnable onBackClick;

    public ReaderToolbar(ReaderCanvas canvas) {
        this.canvas = canvas;

        setAlignment(Pos.CENTER_LEFT);
        setSpacing(5);
        setPadding(new Insets(4, 8, 4, 8));
        setStyle("-fx-background-color: #e0e0e0; -fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");
        setMinHeight(40);
        setPrefHeight(40);

        // ===== НАВІГАЦІЯ =====
        backButton = createButton("←", "Назад (Esc)");
        prevPageButton = createButton("◀", "Попередня сторінка (←)");
        nextPageButton = createButton("▶", "Наступна сторінка (→)");
        prevChapterButton = createButton("⇤", "Попередній розділ (↑)");
        nextChapterButton = createButton("⇥", "Наступний розділ (↓)");

        // ===== РЕЖИМИ =====
        pageModeButton = createButton("▥", "Одна / дві сторінки (P)");
        autoScrollButton = createButton("▶▶", "Автопрокрутка (A)");

        // ===== ЗУМ =====
        zoomOutButton = createButton("🔍−", "Зменшити масштаб (Ctrl+-)");
        zoomInButton = createButton("🔍+", "Збільшити масштаб (Ctrl++)");
        zoomResetButton = createButton("100%", "Скинути масштаб (Ctrl+0)");

        // ===== ВИГЛЯД =====
        themeButton = createButton("🎨", "Змінити тему (T)");
        settingsButton = createButton("⚙️", "Налаштування");
        fullscreenButton = createButton("⛶", "Повноекранний режим (F11)");

        // ===== ФУНКЦІЇ =====
        bookmarkButton = createButton("⭐", "Додати закладку");
        bookmarksButton = createButton("🔖", "Закладки");
        tocButton = createButton("📑", "Зміст");
        searchButton = createButton("🔍", "Пошук (Ctrl+F)");

        // ===== ЗБИРАЄМО =====
        Region spacer = new Region();

        getChildren().addAll(
                backButton,
                new Separator(),
                prevChapterButton,
                prevPageButton,
                nextPageButton,
                nextChapterButton,
                new Separator(),
                pageModeButton,
                autoScrollButton,
                new Separator(),
                zoomOutButton,
                zoomInButton,
                zoomResetButton,
                new Separator(),
                themeButton,
                settingsButton,
                fullscreenButton,
                new Separator(),
                bookmarkButton,
                bookmarksButton,
                tocButton,
                searchButton,
                spacer
        );

        HBox.setHgrow(spacer, Priority.ALWAYS);

        setupActions();
        updateState();

        log.info("✅ ReaderToolbar створено");
    }

    private Button createButton(String text, String tooltip) {
        Button btn = new Button(text);
        btn.setTooltip(new Tooltip(tooltip));
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

        pageModeButton.setStyle(canvas.isTwoPageModeEnabled() ?
                "-fx-font-size: 13px; -fx-padding: 2 6 2 6; -fx-background-color: #4CAF50; -fx-text-fill: white;" :
                "-fx-font-size: 13px; -fx-padding: 2 6 2 6;");
        autoScrollButton.setStyle(canvas.isAutoScrollRunning() ?
                "-fx-font-size: 13px; -fx-padding: 2 6 2 6; -fx-background-color: #FF9800; -fx-text-fill: white;" :
                "-fx-font-size: 13px; -fx-padding: 2 6 2 6;");
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

    public void setOnBackClick(Runnable listener) {
        this.onBackClick = listener;
    }

    public void refresh() {
        updateState();
    }

}
