package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.model.PageLayout;
import com.myhomelibcorp.reader.render.api.ReaderRenderer;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@Slf4j
public class ReaderCanvas extends StackPane {

    private final Canvas canvas;
    private final JavaFxReaderRenderer renderer;
    private final ReaderEngine engine;

    private final PageModeController pageModeController;
    private final AutoScrollController autoScrollController;

    @Getter
    private double zoom = 1.0;
    @Getter
    private PageDimensions currentDimensions;

    private Consumer<ReaderPosition> onPositionChanged;
    private Runnable onPageChanged;
    private Runnable onBookClosed;
    private Consumer<Integer> onPageNumberChanged;

    private boolean isDragging = false;
    private double dragStartX = 0;
    private double dragStartY = 0;

    // Прапорець, щоб уникнути повторних викликів render
    private boolean isRendering = false;
    private boolean isSizeUpdated = false;

    public ReaderCanvas(ReaderEngine engine, ReaderRenderer renderer) {
        this.engine = engine;
        this.renderer = (JavaFxReaderRenderer) renderer;

        this.canvas = new Canvas();
        this.canvas.setFocusTraversable(true);

        this.pageModeController = new PageModeController(canvas);
        this.autoScrollController = new AutoScrollController(canvas);

        getChildren().add(canvas);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((obs, old, val) -> {
            if (val.doubleValue() > 0 && engine.isOpen()) {
                scheduleRender();
            }
        });
        heightProperty().addListener((obs, old, val) -> {
            if (val.doubleValue() > 0 && engine.isOpen()) {
                scheduleRender();
            }
        });

        setFocusTraversable(true);
        setOnKeyPressed(this::onKeyPressed);
        setOnScroll(this::onScroll);
        setOnMouseClicked(this::onMouseClicked);
        setOnMousePressed(this::onMousePressed);
        setOnMouseDragged(this::onMouseDragged);
        setOnMouseReleased(this::onMouseReleased);

        setPadding(new Insets(0));

        log.info("✅ ReaderCanvas створено");
    }

    public void updateSize() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        // Якщо Canvas ще не має розмірів - пробуємо взяти з контейнера
        if (w <= 0 || h <= 0) {
            w = getWidth();
            h = getHeight();
        }

        log.debug("📐 updateSize: width={}, height={}", w, h);

        if (w > 0 && h > 0 && engine.isOpen()) {
            ReaderSettings settings = engine.getSettings();

            currentDimensions = new PageDimensions(
                    (int) w,
                    (int) h,
                    (int) (settings.leftMargin() * zoom),
                    (int) (settings.rightMargin() * zoom),
                    (int) (settings.topMargin() * zoom),
                    (int) (settings.bottomMargin() * zoom)
            );

            isSizeUpdated = true;
            render();
        } else {
            // Якщо розмірів немає - пробуємо ще раз пізніше
            if (engine.isOpen()) {
                javafx.application.Platform.runLater(this::updateSize);
            }
        }
    }

    public void render() {
        if (isRendering) {
            log.trace("⏭️ Рендеринг вже виконується, пропускаємо");
            return;
        }

        if (!engine.isOpen()) {
            clear();
            return;
        }

        double w = canvas.getWidth();
        double h = canvas.getHeight();

        if (w <= 0 || h <= 0) {
            log.warn("⚠️ Немає дійсних розмірів сторінки: w={}, h={}", w, h);
            return;
        }

        isRendering = true;

        try {
            ReaderSettings settings = engine.getSettings();

            currentDimensions = new PageDimensions(
                    (int) w,
                    (int) h,
                    (int) (settings.leftMargin() * zoom),
                    (int) (settings.rightMargin() * zoom),
                    (int) (settings.topMargin() * zoom),
                    (int) (settings.bottomMargin() * zoom)
            );

            engine.renderPage();

            if (onPageChanged != null) {
                onPageChanged.run();
            }
        } finally {
            isRendering = false;
        }
    }

    public void clear() {
        renderer.clear();
    }

    private void scheduleRender() {
        if (!isSizeUpdated) {
            return;
        }
        javafx.application.Platform.runLater(this::render);
    }

    // ==================== НАВІГАЦІЯ ====================

    public void nextPage() {
        if (!engine.isOpen() || currentDimensions == null) {
            return;
        }

        if (pageModeController.isPageModeEnabled()) {
            pageModeController.nextPage();
            if (onPageNumberChanged != null) {
                onPageNumberChanged.accept(pageModeController.getCurrentPage());
            }
            render();
            return;
        }

        engine.nextPage(currentDimensions);
        render();
    }

    public void previousPage() {
        if (!engine.isOpen() || currentDimensions == null) {
            return;
        }

        if (pageModeController.isPageModeEnabled()) {
            pageModeController.previousPage();
            if (onPageNumberChanged != null) {
                onPageNumberChanged.accept(pageModeController.getCurrentPage());
            }
            render();
            return;
        }

        engine.previousPage(currentDimensions);
        render();
    }

    public void previousChapter() {
        if (!engine.isOpen()) {
            return;
        }
        engine.previousChapter();
        render();
    }

    public void nextChapter() {
        if (!engine.isOpen()) {
            return;
        }
        engine.nextChapter();
        render();
    }

    public void goToPercent(double percent) {
        if (!engine.isOpen()) {
            return;
        }
        engine.goToPercent(percent);
        render();
    }

    public void goToPosition(ReaderPosition position) {
        if (!engine.isOpen()) {
            return;
        }

        // Перевіряємо, чи позиція в межах книги
        if (engine.getCurrentDocument() != null) {
            long totalLength = engine.getCurrentDocument().totalTextLength();
            if (position.textOffset() >= totalLength) {
                position = new ReaderPosition(
                        position.chapterIndex(),
                        totalLength > 0 ? totalLength - 1 : 0,
                        position.paragraphIndex(),
                        position.charOffset()
                );
            }
        }

        engine.goToPosition(position);
        render();

        if (onPositionChanged != null) {
            onPositionChanged.accept(position);
        }
    }

    // ==================== СТОРІНКОВИЙ РЕЖИМ ====================

    public void togglePageMode() {
        pageModeController.toggle();
        render();
    }

    public boolean isPageModeEnabled() {
        return pageModeController.isPageModeEnabled();
    }

    public int getCurrentPageNumber() {
        return pageModeController.getCurrentPage();
    }

    public int getTotalPages() {
        return pageModeController.getTotalPages();
    }

    public void goToPage(int page) {
        if (!engine.isOpen() || !pageModeController.isPageModeEnabled()) {
            return;
        }
        pageModeController.goToPage(page);
        if (onPageNumberChanged != null) {
            onPageNumberChanged.accept(pageModeController.getCurrentPage());
        }
        render();
    }

    // ==================== АВТОПРОКРУТКА ====================

    public void toggleAutoScroll() {
        autoScrollController.toggle();
    }

    public boolean isAutoScrollRunning() {
        return autoScrollController.isRunning();
    }

    public void setAutoScrollSpeed(double speed) {
        autoScrollController.setSpeed(speed);
    }

    public double getAutoScrollSpeed() {
        return autoScrollController.getSpeed();
    }

    // ==================== ЗУМ ====================

    public void zoomIn() {
        zoom = Math.min(2.0, zoom + 0.1);
        render();
    }

    public void zoomOut() {
        zoom = Math.max(0.5, zoom - 0.1);
        render();
    }

    public void resetZoom() {
        zoom = 1.0;
        render();
    }

    // ==================== ТЕМИ ====================

    public void cycleTheme() {
        String current = engine.getSettings().themeName();
        String next = switch (current) {
            case "light" -> "sepia";
            case "sepia" -> "dark";
            case "dark" -> "amoled";
            default -> "light";
        };
        updateTheme(next);
    }

    public void updateTheme(String themeName) {
        ReaderSettings newSettings = engine.getSettings().withTheme(themeName);
        applySettings(newSettings);
        log.info("🎨 Тему змінено на: {}", themeName);
    }

    public void applySettings(ReaderSettings settings) {
        engine.applySettings(settings);
        render();
    }

    public void updateFontSize(double size) {
        ReaderSettings newSettings = engine.getSettings().withFontSize(size);
        applySettings(newSettings);
    }

    public void updateFontFamily(String family) {
        ReaderSettings newSettings = engine.getSettings().withFontFamily(family);
        applySettings(newSettings);
    }

    // ==================== ОБРОБКА ПОДІЙ ====================

    private void onKeyPressed(KeyEvent event) {
        KeyCode code = event.getCode();

        if (code == KeyCode.PAGE_DOWN || code == KeyCode.RIGHT || code == KeyCode.SPACE) {
            event.consume();
            if (event.isShiftDown()) {
                previousPage();
            } else {
                nextPage();
            }
        } else if (code == KeyCode.PAGE_UP || code == KeyCode.LEFT) {
            event.consume();
            previousPage();
        } else if (code == KeyCode.HOME) {
            event.consume();
            goToPercent(0);
        } else if (code == KeyCode.END) {
            event.consume();
            goToPercent(100);
        } else if (code == KeyCode.UP) {
            event.consume();
            previousChapter();
        } else if (code == KeyCode.DOWN) {
            event.consume();
            nextChapter();
        } else if (code == KeyCode.P) {
            event.consume();
            togglePageMode();
        } else if (code == KeyCode.A) {
            event.consume();
            toggleAutoScroll();
        } else if (code == KeyCode.ADD || code == KeyCode.PLUS) {
            event.consume();
            if (event.isControlDown()) {
                zoomIn();
            }
        } else if (code == KeyCode.SUBTRACT || code == KeyCode.MINUS) {
            event.consume();
            if (event.isControlDown()) {
                zoomOut();
            }
        } else if (code == KeyCode.DIGIT0 && event.isControlDown()) {
            event.consume();
            resetZoom();
        } else if (code == KeyCode.T) {
            event.consume();
            cycleTheme();
        } else if (code == KeyCode.F11) {
            event.consume();
            toggleFullscreen();
        } else if (code == KeyCode.ESCAPE) {
            event.consume();
            if (autoScrollController.isRunning()) {
                autoScrollController.stop();
            } else if (onBookClosed != null) {
                onBookClosed.run();
            }
        }
    }

    private void onScroll(ScrollEvent event) {
        if (event.isControlDown()) {
            double delta = event.getDeltaY() / 100;
            zoom = Math.max(0.5, Math.min(2.0, zoom + delta));
            render();
            event.consume();
        } else if (event.getDeltaY() > 0) {
            previousPage();
            event.consume();
        } else if (event.getDeltaY() < 0) {
            nextPage();
            event.consume();
        }
    }

    private void onMouseClicked(MouseEvent event) {
        if (!engine.isOpen()) {
            return;
        }

        double x = event.getX();
        double w = canvas.getWidth();

        if (x < w / 3) {
            previousPage();
            event.consume();
        } else if (x > w * 2 / 3) {
            nextPage();
            event.consume();
        }
    }

    private void onMousePressed(MouseEvent event) {
        if (event.isPrimaryButtonDown() && event.isControlDown()) {
            isDragging = true;
            dragStartX = event.getX();
            dragStartY = event.getY();
            event.consume();
        }
    }

    private void onMouseDragged(MouseEvent event) {
        if (!isDragging || !engine.isOpen()) {
            return;
        }

        double dx = event.getX() - dragStartX;

        if (Math.abs(dx) > 50) {
            if (dx > 0) {
                previousPage();
            } else {
                nextPage();
            }
            isDragging = false;
            event.consume();
        }
    }

    private void onMouseReleased(MouseEvent event) {
        isDragging = false;
    }

    private void toggleFullscreen() {
        javafx.stage.Stage stage = (javafx.stage.Stage) getScene().getWindow();
        if (stage != null) {
            stage.setFullScreen(!stage.isFullScreen());
        }
    }

    // ==================== ЗАКРИТТЯ ====================

    public void closeBook() {
        if (engine.isOpen()) {
            if (autoScrollController.isRunning()) {
                autoScrollController.stop();
            }
            engine.close();
            clear();
            isSizeUpdated = false;
            if (onBookClosed != null) {
                onBookClosed.run();
            }
            log.info("📖 Книгу закрито через ReaderCanvas");
        }
    }

    // ==================== КОЛБЕКИ ====================

    public void setOnPositionChanged(Consumer<ReaderPosition> listener) {
        this.onPositionChanged = listener;
    }

    public void setOnPageChanged(Runnable listener) {
        this.onPageChanged = listener;
    }

    public void setOnBookClosed(Runnable listener) {
        this.onBookClosed = listener;
    }

    public void setOnPageNumberChanged(Consumer<Integer> listener) {
        this.onPageNumberChanged = listener;
    }

    // ==================== СТАН ====================

    public ReaderEngine getEngine() {
        return engine;
    }

    public JavaFxReaderRenderer getRenderer() {
        return renderer;
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public double getProgressPercent() {
        return engine.getProgressPercent();
    }

    public String getCurrentChapterTitle() {
        return engine.getCurrentChapterTitle();
    }

    public ReaderPosition getCurrentPosition() {
        return engine.getCurrentPosition();
    }

    public boolean isBookOpen() {
        return engine.isOpen();
    }

    public String getCacheStats() {
        return engine.getCacheStats();
    }

    public boolean isSizeUpdated() {
        return isSizeUpdated;
    }

    // ==================== ЗВІЛЬНЕННЯ РЕСУРСІВ ====================

    public void dispose() {
        if (autoScrollController.isRunning()) {
            autoScrollController.stop();
        }
        if (engine.isOpen()) {
            engine.close();
        }
        renderer.clear();
        renderer.clearFontCache();
        renderer.clearImageCache();
        isSizeUpdated = false;
        log.info("🧹 ReaderCanvas знищено");
    }
}