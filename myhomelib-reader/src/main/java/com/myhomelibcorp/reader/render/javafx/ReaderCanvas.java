package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.model.PageLayout;
import com.myhomelibcorp.reader.render.api.ReaderRenderer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.SwipeEvent;
import javafx.scene.layout.StackPane;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * JavaFX viewport reader-а. Важливо: використовує ТОЙ САМИЙ Canvas, який
 * належить JavaFxReaderRenderer. Раніше тут створювався другий Canvas, через
 * що renderer малював у невидимий вузол.
 */
@Slf4j
public class ReaderCanvas extends StackPane {

    private static final int MAX_PAGE_HISTORY = 256;

    private final Canvas canvas;
    private final JavaFxReaderRenderer renderer;
    private final ReaderEngine engine;
    private final AutoScrollController autoScrollController;
    private final Deque<ReaderPosition> pageHistory = new ArrayDeque<>();

    @Getter
    private double zoom = 1.0;
    @Getter
    private PageDimensions currentDimensions;

    private double zoomBaseFontSize;
    private boolean pageModeEnabled;
    private boolean rendering;
    private boolean renderScheduled;
    private boolean sizeUpdated;
    private boolean dragging;
    private boolean swipeHandled;
    private double dragStartX;
    private double dragStartY;
    private double accumulatedScroll;
    private long lastWheelPageNanos;

    private Consumer<ReaderPosition> onPositionChanged;
    private Runnable onPageChanged;
    private Runnable onBookClosed;
    private Runnable onCloseRequested;
    private Consumer<Integer> onPageNumberChanged;
    private Runnable onCenterTap;
    private Runnable onSearchRequested;

    public ReaderCanvas(ReaderEngine engine, ReaderRenderer renderer) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }
        if (!(renderer instanceof JavaFxReaderRenderer fxRenderer)) {
            throw new IllegalArgumentException("ReaderCanvas requires JavaFxReaderRenderer");
        }

        this.engine = engine;
        this.renderer = fxRenderer;
        this.canvas = fxRenderer.getCanvas(); // критичний fix: один спільний Canvas
        this.zoomBaseFontSize = engine.getSettings().fontSize();
        this.pageModeEnabled = engine.getSettings().pageMode();
        this.autoScrollController = new AutoScrollController(this::nextPage);

        canvas.setFocusTraversable(true);
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((obs, oldValue, newValue) -> scheduleRender());
        heightProperty().addListener((obs, oldValue, newValue) -> scheduleRender());

        setFocusTraversable(true);
        setOnKeyPressed(this::onKeyPressed);
        setOnScroll(this::onScroll);
        setOnMouseClicked(this::onMouseClicked);
        setOnMousePressed(this::onMousePressed);
        setOnMouseDragged(this::onMouseDragged);
        setOnMouseReleased(this::onMouseReleased);
        setOnSwipeLeft(this::onSwipeLeft);
        setOnSwipeRight(this::onSwipeRight);
        setPadding(Insets.EMPTY);
    }

    public void updateSize() {
        if (!engine.isOpen()) {
            return;
        }
        if (canvas.getWidth() > 0 && canvas.getHeight() > 0) {
            render();
        }
        // Якщо layout ще не відбувся, width/height listeners самі викличуть render.
    }

    public void render() {
        if (rendering) {
            return;
        }
        if (!engine.isOpen()) {
            clear();
            return;
        }

        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 1 || h <= 1) {
            return;
        }

        rendering = true;
        try {
            ReaderSettings settings = engine.getSettings();
            PageDimensions newDimensions = new PageDimensions(
                    Math.max(1, (int) Math.floor(w)),
                    Math.max(1, (int) Math.floor(h)),
                    Math.max(0, (int) Math.round(settings.leftMargin())),
                    Math.max(0, (int) Math.round(settings.rightMargin())),
                    Math.max(0, (int) Math.round(settings.topMargin())),
                    Math.max(0, (int) Math.round(settings.bottomMargin()))
            );
            if (currentDimensions != null && !currentDimensions.equals(newDimensions)) {
                pageHistory.clear();
            }
            currentDimensions = newDimensions;
            sizeUpdated = currentDimensions.isValid();
            if (!sizeUpdated) {
                return;
            }

            // Другий критичний fix: dimensions передаються engine ДО renderPage.
            engine.renderPage(currentDimensions);
            notifyPageChanged();
        } finally {
            rendering = false;
        }
    }

    public void clear() {
        renderer.clear();
    }

    private void scheduleRender() {
        if (!engine.isOpen() || renderScheduled) {
            return;
        }
        renderScheduled = true;
        Platform.runLater(() -> {
            renderScheduled = false;
            render();
        });
    }

    // ==================== НАВІГАЦІЯ ====================

    public void nextPage() {
        if (!engine.isOpen() || !ensureDimensions()) {
            return;
        }
        ReaderPosition before = engine.getCurrentPosition();
        engine.nextPage(currentDimensions);
        ReaderPosition after = engine.getCurrentPosition();
        if (!sameOffset(before, after)) {
            pushHistory(before);
            render();
            notifyPositionChanged();
        } else if (autoScrollController.isRunning()) {
            autoScrollController.stop();
        }
    }

    public void previousPage() {
        if (!engine.isOpen() || !ensureDimensions()) {
            return;
        }
        ReaderPosition before = engine.getCurrentPosition();
        ReaderPosition target = pageHistory.pollLast();
        if (target != null) {
            engine.goToPosition(target);
        } else {
            engine.previousPage(currentDimensions);
        }
        if (!sameOffset(before, engine.getCurrentPosition())) {
            render();
            notifyPositionChanged();
        }
    }

    public void previousChapter() {
        if (!engine.isOpen()) {
            return;
        }
        ReaderPosition before = engine.getCurrentPosition();
        pageHistory.clear();
        engine.previousChapter();
        if (!sameOffset(before, engine.getCurrentPosition())) {
            render();
            notifyPositionChanged();
        }
    }

    public void nextChapter() {
        if (!engine.isOpen()) {
            return;
        }
        ReaderPosition before = engine.getCurrentPosition();
        pageHistory.clear();
        engine.nextChapter();
        if (!sameOffset(before, engine.getCurrentPosition())) {
            render();
            notifyPositionChanged();
        }
    }

    public void goToPercent(double percent) {
        if (!engine.isOpen()) {
            return;
        }
        pageHistory.clear();
        engine.goToPercent(percent);
        render();
        notifyPositionChanged();
    }

    public void goToPosition(ReaderPosition position) {
        if (!engine.isOpen() || position == null) {
            return;
        }
        pageHistory.clear();
        engine.goToPosition(position);
        render();
        notifyPositionChanged();
    }

    private void pushHistory(ReaderPosition position) {
        if (position == null) {
            return;
        }
        if (pageHistory.size() >= MAX_PAGE_HISTORY) {
            pageHistory.pollFirst();
        }
        pageHistory.addLast(position);
    }

    private boolean sameOffset(ReaderPosition a, ReaderPosition b) {
        return a == null ? b == null : b != null && a.textOffset() == b.textOffset();
    }

    private boolean ensureDimensions() {
        if (currentDimensions != null && currentDimensions.isValid()) {
            return true;
        }
        render();
        return currentDimensions != null && currentDimensions.isValid();
    }

    // ==================== РЕЖИМИ ====================

    public void togglePageMode() {
        pageModeEnabled = !pageModeEnabled;
    }

    public boolean isPageModeEnabled() {
        return pageModeEnabled;
    }

    /** Номер приблизний; повну карту сторінок навмисно не тримаємо в RAM. */
    public int getCurrentPageNumber() {
        if (!engine.isOpen() || currentDimensions == null) {
            return 1;
        }
        long pageSize = currentPageLength();
        return (int) Math.max(1, engine.getCurrentPosition().textOffset() / pageSize + 1);
    }

    public int getTotalPages() {
        if (!engine.isOpen() || currentDimensions == null || engine.getCurrentDocument() == null) {
            return 1;
        }
        long pageSize = currentPageLength();
        long total = engine.getCurrentDocument().totalTextLength();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (total + pageSize - 1) / pageSize));
    }

    private long currentPageLength() {
        PageLayout page = engine.getCurrentPage(currentDimensions);
        return Math.max(1, page.getEndOffset() - page.getStartOffset());
    }

    public void goToPage(int page) {
        if (page <= 1) {
            goToPercent(0);
            return;
        }
        int total = getTotalPages();
        double percent = Math.min(100.0, (page - 1) * 100.0 / Math.max(1, total));
        goToPercent(percent);
        if (onPageNumberChanged != null) {
            onPageNumberChanged.accept(getCurrentPageNumber());
        }
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

    // ==================== ЗУМ / НАЛАШТУВАННЯ ====================

    public void zoomIn() {
        zoom = Math.min(2.0, zoom + 0.1);
        applyZoom();
    }

    public void zoomOut() {
        zoom = Math.max(0.55, zoom - 0.1);
        applyZoom();
    }

    public void resetZoom() {
        zoom = 1.0;
        applyZoom();
    }

    private void applyZoom() {
        pageHistory.clear();
        ReaderSettings effective = engine.getSettings().withFontSize(
                Math.max(8, Math.min(72, zoomBaseFontSize * zoom))
        );
        renderer.applySettings(effective);
        engine.applySettings(effective);
        render();
    }

    public void cycleTheme() {
        String current = engine.getSettings().themeName();
        String next = switch (current) {
            case "light" -> "sepia";
            case "sepia" -> "dark";
            case "dark" -> "amoled";
            default -> "light";
        };
        ReaderSettings themed = engine.getSettings().withTheme(next);
        renderer.applySettings(themed);
        engine.applySettings(themed);
        render();
    }

    public void updateTheme(String themeName) {
        ReaderSettings themed = engine.getSettings().withTheme(themeName);
        renderer.applySettings(themed);
        engine.applySettings(themed);
        render();
    }

    public void applySettings(ReaderSettings settings) {
        if (settings == null) {
            return;
        }
        zoom = 1.0;
        zoomBaseFontSize = settings.fontSize();
        pageModeEnabled = settings.pageMode();
        pageHistory.clear();
        renderer.applySettings(settings);
        engine.applySettings(settings);
        render();
    }

    public void updateFontSize(double size) {
        zoom = 1.0;
        zoomBaseFontSize = Math.max(8, Math.min(72, size));
        applySettings(engine.getSettings().withFontSize(zoomBaseFontSize));
    }

    public void updateFontFamily(String family) {
        applySettings(engine.getSettings().withFontFamily(family));
    }

    // ==================== INPUT ====================

    private void onKeyPressed(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == KeyCode.PAGE_DOWN || code == KeyCode.RIGHT || code == KeyCode.SPACE) {
            event.consume();
            if (event.isShiftDown()) previousPage(); else nextPage();
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
            notifyPageChanged();
        } else if (code == KeyCode.A) {
            event.consume();
            toggleAutoScroll();
            notifyPageChanged();
        } else if ((code == KeyCode.ADD || code == KeyCode.PLUS) && event.isControlDown()) {
            event.consume();
            zoomIn();
        } else if ((code == KeyCode.SUBTRACT || code == KeyCode.MINUS) && event.isControlDown()) {
            event.consume();
            zoomOut();
        } else if (code == KeyCode.DIGIT0 && event.isControlDown()) {
            event.consume();
            resetZoom();
        } else if (code == KeyCode.T) {
            event.consume();
            cycleTheme();
        } else if (code == KeyCode.F && event.isControlDown()) {
            event.consume();
            if (onSearchRequested != null) onSearchRequested.run();
        } else if (code == KeyCode.F11) {
            event.consume();
            toggleFullscreen();
        } else if (code == KeyCode.ESCAPE) {
            event.consume();
            if (autoScrollController.isRunning()) {
                autoScrollController.stop();
            } else if (onCloseRequested != null) {
                onCloseRequested.run();
            } else {
                closeBook();
            }
        }
    }

    private void onScroll(ScrollEvent event) {
        if (event.isControlDown()) {
            if (event.getDeltaY() > 0) zoomIn();
            else if (event.getDeltaY() < 0) zoomOut();
            event.consume();
            return;
        }

        // Touchpad/mouse-wheel може відправляти десятки дрібних подій. Накопичуємо
        // дельту і робимо максимум один page turn за короткий інтервал.
        accumulatedScroll += event.getDeltaY();
        long now = System.nanoTime();
        boolean enoughDelta = Math.abs(accumulatedScroll) >= 35.0;
        boolean debouncePassed = now - lastWheelPageNanos >= 160_000_000L;
        if (enoughDelta && debouncePassed) {
            if (accumulatedScroll > 0) previousPage();
            else nextPage();
            accumulatedScroll = 0;
            lastWheelPageNanos = now;
        }
        event.consume();
    }

    private void onMouseClicked(MouseEvent event) {
        if (!engine.isOpen()) return;
        if (swipeHandled) {
            swipeHandled = false;
            event.consume();
            requestFocus();
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
        } else if (onCenterTap != null) {
            onCenterTap.run();
            event.consume();
        }
        requestFocus();
    }

    private void onMousePressed(MouseEvent event) {
        if (event.isPrimaryButtonDown()) {
            dragging = true;
            swipeHandled = false;
            dragStartX = event.getX();
            dragStartY = event.getY();
        }
    }

    private void onMouseDragged(MouseEvent event) {
        if (!dragging || !engine.isOpen() || swipeHandled) return;
        double dx = event.getX() - dragStartX;
        double dy = event.getY() - dragStartY;
        if (Math.abs(dx) >= 55 && Math.abs(dx) > Math.abs(dy) * 1.2) {
            if (dx > 0) previousPage();
            else nextPage();
            swipeHandled = true;
            event.consume();
        }
    }

    private void onMouseReleased(MouseEvent event) {
        dragging = false;
    }

    private void onSwipeLeft(SwipeEvent event) {
        if (engine.isOpen()) {
            nextPage();
            swipeHandled = true;
            event.consume();
        }
    }

    private void onSwipeRight(SwipeEvent event) {
        if (engine.isOpen()) {
            previousPage();
            swipeHandled = true;
            event.consume();
        }
    }

    private void toggleFullscreen() {
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) {
            stage.setFullScreen(!stage.isFullScreen());
        }
    }

    // ==================== LIFECYCLE / CALLBACKS ====================

    public void closeBook() {
        if (!engine.isOpen()) return;
        autoScrollController.stop();
        engine.close();
        renderer.setResourceRepository(null);
        clear();
        pageHistory.clear();
        sizeUpdated = false;
        if (onBookClosed != null) onBookClosed.run();
    }

    private void notifyPositionChanged() {
        if (onPositionChanged != null && engine.getCurrentPosition() != null) {
            onPositionChanged.accept(engine.getCurrentPosition());
        }
        notifyPageChanged();
    }

    private void notifyPageChanged() {
        if (onPageChanged != null) onPageChanged.run();
        if (onPageNumberChanged != null && engine.isOpen()) {
            onPageNumberChanged.accept(getCurrentPageNumber());
        }
    }

    public void setOnPositionChanged(Consumer<ReaderPosition> listener) {
        this.onPositionChanged = listener;
    }

    public void setOnPageChanged(Runnable listener) {
        this.onPageChanged = listener;
    }

    public void setOnBookClosed(Runnable listener) {
        this.onBookClosed = listener;
    }

    public void setOnCloseRequested(Runnable listener) {
        this.onCloseRequested = listener;
    }

    public void setOnPageNumberChanged(Consumer<Integer> listener) {
        this.onPageNumberChanged = listener;
    }

    public void setOnCenterTap(Runnable listener) {
        this.onCenterTap = listener;
    }

    public void setOnSearchRequested(Runnable listener) {
        this.onSearchRequested = listener;
    }

    public ReaderEngine getEngine() { return engine; }
    public JavaFxReaderRenderer getRenderer() { return renderer; }
    public Canvas getCanvas() { return canvas; }
    public double getProgressPercent() { return engine.getProgressPercent(); }
    public String getCurrentChapterTitle() { return engine.getCurrentChapterTitle(); }
    public ReaderPosition getCurrentPosition() { return engine.getCurrentPosition(); }
    public boolean isBookOpen() { return engine.isOpen(); }
    public String getCacheStats() { return engine.getCacheStats(); }
    public boolean isSizeUpdated() { return sizeUpdated; }

    public void dispose() {
        autoScrollController.stop();
        if (engine.isOpen()) engine.close();
        renderer.setResourceRepository(null);
        renderer.clear();
        renderer.clearFontCache();
        renderer.clearImageCache();
        pageHistory.clear();
        sizeUpdated = false;
    }
}
