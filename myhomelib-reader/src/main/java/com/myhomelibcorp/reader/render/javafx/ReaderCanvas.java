package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderTheme;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.model.PageLayout;
import com.myhomelibcorp.reader.render.api.ReaderRenderer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.SwipeEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * JavaFX viewport reader-а. Важливо: використовує ТОЙ САМИЙ Canvas, який
 * належить JavaFxReaderRenderer. Раніше тут створювався другий Canvas, через
 * що renderer малював у невидимий вузол.
 */
@Slf4j
public class ReaderCanvas extends StackPane {

    private final Canvas canvas;
    private final JavaFxReaderRenderer renderer;
    private final ReaderEngine engine;
    private final AutoScrollController autoScrollController;
    private final ReaderPageHistory pageHistory = new ReaderPageHistory(256);
    private final ReaderPaginationController paginationController;
    private final ReaderSelectionController selectionController;
    private final ReaderKeyboardScrollController keyboardScrollController;

    @Getter
    private double zoom = 1.0;
    @Getter
    private PageDimensions currentDimensions;

    private static final double SPREAD_GUTTER = 24.0;
    private static final long LONG_PRESS_MS = 520L;

    private double zoomBaseFontSize;
    private boolean rendering;
    private boolean renderScheduled;
    private boolean sizeUpdated;
    private boolean dragging;
    private boolean swipeHandled;
    private boolean longPressHandled;
    private double dragStartX;
    private double dragStartY;
    private final PauseTransition longPressTimer = new PauseTransition(Duration.millis(LONG_PRESS_MS));
    private PageLayout renderedLeftPage = PageLayout.empty();
    private PageLayout renderedRightPage = PageLayout.empty();
    private double renderedRightOffset;
    private boolean twoPageActive;

    private Consumer<ReaderPosition> onPositionChanged;
    private Runnable onPageChanged;
    private Runnable onBookClosed;
    private Runnable onCloseRequested;
    private Consumer<Integer> onPageNumberChanged;
    private Runnable onCenterTap;
    private Runnable onToggleToolbarRequested;
    private Runnable onSearchRequested;
    private Consumer<ReaderSettings> onSettingsChanged;

    public ReaderCanvas(ReaderEngine engine, ReaderRenderer renderer) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }
        if (!(renderer instanceof JavaFxReaderRenderer fxRenderer)) {
            throw new IllegalArgumentException("ReaderCanvas requires JavaFxReaderRenderer");
        }

        this.engine = engine;
        this.paginationController = new ReaderPaginationController(engine);
        this.renderer = fxRenderer;
        this.canvas = fxRenderer.getCanvas(); // критичний fix: один спільний Canvas
        this.zoomBaseFontSize = engine.getSettings().fontSize();
        this.autoScrollController = new AutoScrollController(this::nextPage);
        this.selectionController = new ReaderSelectionController(engine, fxRenderer);
        this.keyboardScrollController = new ReaderKeyboardScrollController(this);

        canvas.setFocusTraversable(true);
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((obs, oldValue, newValue) -> scheduleRender());
        heightProperty().addListener((obs, oldValue, newValue) -> scheduleRender());

        setFocusTraversable(true);
        setOnKeyPressed(keyboardScrollController::onKeyPressed);
        setOnScroll(keyboardScrollController::onScroll);
        setOnMouseClicked(this::onMouseClicked);
        setOnMousePressed(this::onMousePressed);
        setOnMouseDragged(this::onMouseDragged);
        setOnMouseReleased(this::onMouseReleased);
        setOnSwipeLeft(this::onSwipeLeft);
        setOnSwipeRight(this::onSwipeRight);
        setOnSwipeUp(this::onSwipeUp);
        setOnSwipeDown(this::onSwipeDown);
        setOnZoom(this::onZoom);
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
        if (rendering) return;
        if (!engine.isOpen()) {
            clear();
            return;
        }

        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 1 || h <= 1) return;

        rendering = true;
        try {
            ReaderSettings settings = engine.getSettings();
            twoPageActive = settings.twoPageMode()
                    || (settings.autoTwoPageLandscape() && w >= h * 1.18 && w >= 760);
            double pageViewportWidth = twoPageActive ? Math.max(1, (w - SPREAD_GUTTER) / 2.0) : w;
            PageDimensions newDimensions = new PageDimensions(
                    Math.max(1, (int) Math.floor(pageViewportWidth)),
                    Math.max(1, (int) Math.floor(h)),
                    Math.max(0, (int) Math.round(settings.leftMargin())),
                    Math.max(0, (int) Math.round(settings.rightMargin())),
                    Math.max(0, (int) Math.round(settings.topMargin())),
                    Math.max(0, (int) Math.round(settings.bottomMargin()))
            );
            if (currentDimensions != null && !currentDimensions.equals(newDimensions)) {
                pageHistory.clear();
                paginationController.invalidate();
            }
            currentDimensions = newDimensions;
            sizeUpdated = currentDimensions.isValid();
            if (!sizeUpdated) return;
            paginationController.prepare(currentDimensions, this::notifyPageChanged);

            renderedLeftPage = engine.getCurrentPage(currentDimensions);
            renderedRightPage = PageLayout.empty();
            renderedRightOffset = 0;
            if (twoPageActive && renderedLeftPage != null && !renderedLeftPage.isEmpty()) {
                renderedRightOffset = pageViewportWidth + SPREAD_GUTTER;
                if (engine.getCurrentDocument() != null
                        && renderedLeftPage.getEndOffset() < engine.getCurrentDocument().totalTextLength()) {
                    renderedRightPage = engine.getPageAt(renderedLeftPage.getEndOffset(), currentDimensions);
                }
                // Keep the spread layout active even on the final single page; the right leaf remains blank.
                renderer.renderSpread(renderedLeftPage, renderedRightPage, ReaderTheme.fromSettings(settings),
                        renderedRightOffset, pageViewportWidth);
            } else {
                engine.renderPage(currentDimensions);
            }
            renderSelectionOverlay();
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
        clearSelection(false);
        if (!engine.isOpen() || !ensureDimensions()) return;
        ReaderPosition before = engine.getCurrentPosition();
        engine.nextPage(currentDimensions);
        if (twoPageActive && !sameOffset(before, engine.getCurrentPosition())) {
            engine.nextPage(currentDimensions);
        }
        ReaderPosition after = engine.getCurrentPosition();
        if (!sameOffset(before, after)) {
            pageHistory.push(before);
            render();
            notifyPositionChanged();
        } else if (autoScrollController.isRunning()) {
            autoScrollController.stop();
        }
    }

    public void previousPage() {
        clearSelection(false);
        if (!engine.isOpen() || !ensureDimensions()) {
            return;
        }
        ReaderPosition before = engine.getCurrentPosition();
        ReaderPosition target = pageHistory.pollLast();
        if (target != null) {
            engine.goToPosition(target);
        } else {
            engine.previousPage(currentDimensions);
            if (twoPageActive && engine.getCurrentPosition() != null && engine.getCurrentPosition().textOffset() > 0) {
                engine.previousPage(currentDimensions);
            }
        }
        if (!sameOffset(before, engine.getCurrentPosition())) {
            render();
            notifyPositionChanged();
        }
    }

    public void previousChapter() {
        clearSelection(false);
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
        clearSelection(false);
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
        clearSelection(false);
        if (!engine.isOpen()) {
            return;
        }
        pageHistory.clear();
        engine.goToPercent(percent);
        render();
        notifyPositionChanged();
    }

    public void goToPosition(ReaderPosition position) {
        clearSelection(false);
        if (!engine.isOpen() || position == null) {
            return;
        }
        pageHistory.clear();
        engine.goToPosition(position);
        render();
        notifyPositionChanged();
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


    public void toggleTwoPageMode() {
        ReaderSettings current = engine.getSettings();
        boolean active = isTwoPageModeEnabled();
        ReaderSettings changed = new ReaderSettings(
                current.themeName(), current.fontFamily(), current.fontSize(), current.lineSpacing(),
                current.paragraphSpacing(), current.firstLineIndent(), current.alignment(),
                current.leftMargin(), current.rightMargin(), current.topMargin(), current.bottomMargin(),
                current.hyphenation(), current.pageMode(), current.autoScroll(), current.scrollSpeed(),
                current.showToolbar(), current.customCss(), current.showStatusBar(), current.showStatusProgress(),
                current.showStatusChapter(), current.showStatusPage(), current.tapLeftAction(), current.tapCenterAction(),
                current.tapRightAction(), !active, active ? false : current.autoTwoPageLandscape(),
                current.showStatusClock(), current.input());
        pageHistory.clear();
        paginationController.invalidate();
        engine.applySettings(changed);
        render();
        notifySettingsChanged();
    }

    public boolean isTwoPageModeEnabled() { return twoPageActive; }


    /** Exact canonical page number when indexed; 0 means pagination is still in progress. */
    public int getCurrentPageNumber() {
        if (!engine.isOpen() || currentDimensions == null || engine.getCurrentPosition() == null) return 0;
        paginationController.prepare(currentDimensions, this::notifyPageChanged);
        return paginationController.pageForOffset(engine.getCurrentPosition().textOffset());
    }

    /** Exact total page count; 0 while the compact PageIndex is still being calculated. */
    public int getTotalPages() {
        if (!engine.isOpen() || currentDimensions == null || engine.getCurrentDocument() == null) return 0;
        paginationController.prepare(currentDimensions, this::notifyPageChanged);
        return paginationController.totalPages();
    }

    public void goToPage(int page) {
        if (!engine.isOpen() || page <= 0) return;
        paginationController.prepare(currentDimensions, this::notifyPageChanged);
        var target = paginationController.offsetForPage(page);
        if (target.isEmpty()) return; // Never approximate a page that has not been indexed yet.
        clearSelection(false);
        pageHistory.clear();
        long offset = target.getAsLong();
        engine.goToPosition(new ReaderPosition(engine.getCurrentDocument().chapterIndexAt(offset), offset, 0, 0));
        render();
        notifyPositionChanged();
    }

    // ==================== АВТОПРОКРУТКА ====================

    public void toggleAutoScroll() {
        autoScrollController.toggle();
        engine.applySettings(engine.getSettings().withAutoScroll(autoScrollController.isRunning()));
        notifySettingsChanged();
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
        paginationController.invalidate();
        engine.applySettings(effective);
        render();
        notifySettingsChanged();
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
        notifySettingsChanged();
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
        pageHistory.clear();
        paginationController.invalidate();
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

    private void onMouseClicked(MouseEvent event) {
        if (!engine.isOpen()) return;
        if (event.isShiftDown()) {
            event.consume();
            requestFocus();
            return;
        }
        if (swipeHandled || longPressHandled) {
            swipeHandled = false;
            longPressHandled = false;
            event.consume();
            requestFocus();
            return;
        }
        executeTapAction(tapActionAt(event.getX(), event.getY(), false));
        event.consume();
        requestFocus();
    }

    private String tapActionAt(double x, double y, boolean longPress) {
        double w = Math.max(1.0, canvas.getWidth());
        double h = Math.max(1.0, canvas.getHeight());
        ReaderSettings settings = engine.getSettings();
        return settings.input().tapAction(
                Math.max(0, Math.min(1, x / w)),
                Math.max(0, Math.min(1, y / h)),
                longPress);
    }

    private void executeTapAction(String action) {
        String normalized = action == null ? "none" : action.trim().toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "previous-page" -> previousPage();
            case "next-page" -> nextPage();
            case "previous-chapter" -> previousChapter();
            case "next-chapter" -> nextChapter();
            case "start" -> goToPercent(0);
            case "end" -> goToPercent(100);
            case "toggle-two-page" -> toggleTwoPageMode();
            case "toggle-auto-scroll" -> toggleAutoScroll();
            case "zoom-in" -> zoomIn();
            case "zoom-out" -> zoomOut();
            case "theme" -> cycleTheme();
            case "toggle-toolbar" -> {
                if (onToggleToolbarRequested != null) onToggleToolbarRequested.run();
                else if (onCenterTap != null) onCenterTap.run();
            }
            case "search" -> { if (onSearchRequested != null) onSearchRequested.run(); }
            case "none" -> { }
            default -> log.debug("Unknown Reader input action ignored: {}", normalized);
        }
    }

    private void onMousePressed(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) return;
        longPressTimer.stop();
        longPressHandled = false;
        if (event.isShiftDown() && engine.isOpen() && ensureDimensions()) {
            dragging = false;
            SelectionPage selectionPage = selectionPageAt(event.getX());
            selectionController.begin(event.getX(), event.getY(), selectionPage.page(), selectionPage.xOffset());
            render();
            event.consume();
            return;
        }
        dragging = true;
        selectionController.clear();
        swipeHandled = false;
        dragStartX = event.getX();
        dragStartY = event.getY();
        longPressTimer.setOnFinished(ignored -> {
            if (dragging && engine.isOpen() && !swipeHandled && !selectionController.isSelecting()) {
                executeTapAction(tapActionAt(dragStartX, dragStartY, true));
                longPressHandled = true;
                dragging = false;
            }
        });
        longPressTimer.playFromStart();
    }

    private void onMouseDragged(MouseEvent event) {
        if (selectionController.isSelecting() && engine.isOpen()) {
            SelectionPage selectionPage = selectionPageAt(event.getX());
            selectionController.drag(event.getX(), event.getY(), selectionPage.page(), selectionPage.xOffset());
            render();
            event.consume();
            return;
        }
        if (!dragging || !engine.isOpen() || swipeHandled) return;
        double dx = event.getX() - dragStartX;
        double dy = event.getY() - dragStartY;
        if (Math.hypot(dx, dy) > 10) longPressTimer.stop();
        if (Math.max(Math.abs(dx), Math.abs(dy)) < 55) return;
        if (Math.abs(dx) > Math.abs(dy) * 1.2) {
            executeTapAction(dx > 0 ? engine.getSettings().input().swipeRight() : engine.getSettings().input().swipeLeft());
        } else if (Math.abs(dy) > Math.abs(dx) * 1.2) {
            executeTapAction(dy > 0 ? engine.getSettings().input().swipeDown() : engine.getSettings().input().swipeUp());
        } else {
            return;
        }
        swipeHandled = true;
        event.consume();
    }

    private void onMouseReleased(MouseEvent event) {
        longPressTimer.stop();
        dragging = false;
        if (selectionController.isSelecting()) {
            SelectionPage selectionPage = selectionPageAt(event.getX());
            selectionController.finish(event.getX(), event.getY(), selectionPage.page(), selectionPage.xOffset());
            render();
            event.consume();
            requestFocus();
        }
    }

    private SelectionPage selectionPageAt(double x) {
        if (twoPageActive && renderedRightPage != null && !renderedRightPage.isEmpty() && x >= renderedRightOffset) {
            return new SelectionPage(renderedRightPage, renderedRightOffset);
        }
        return new SelectionPage(renderedLeftPage, 0.0);
    }

    private record SelectionPage(PageLayout page, double xOffset) { }

    private boolean hasSelection() { return selectionController.hasSelection(); }

    private void clearSelection(boolean renderNow) {
        selectionController.clear();
        if (renderNow && engine.isOpen()) render();
    }

    private void renderSelectionOverlay() {
        selectionController.renderOverlay(renderedLeftPage, 0.0);
        if (twoPageActive && renderedRightPage != null && !renderedRightPage.isEmpty()) {
            selectionController.renderOverlay(renderedRightPage, renderedRightOffset);
        }
    }

    void copySelectionFromInput() { selectionController.copyToClipboard(); }

    void notifyPageChangedFromInput() { notifyPageChanged(); }

    void requestSearchFromInput() {
        if (onSearchRequested != null) onSearchRequested.run();
    }

    void handleEscapeFromInput() {
        if (hasSelection()) {
            clearSelection(true);
        } else if (autoScrollController.isRunning()) {
            autoScrollController.stop();
        } else if (onCloseRequested != null) {
            onCloseRequested.run();
        } else {
            closeBook();
        }
    }

    private void onSwipeLeft(SwipeEvent event) { executeSwipe(event, engine.getSettings().input().swipeLeft()); }
    private void onSwipeRight(SwipeEvent event) { executeSwipe(event, engine.getSettings().input().swipeRight()); }
    private void onSwipeUp(SwipeEvent event) { executeSwipe(event, engine.getSettings().input().swipeUp()); }
    private void onSwipeDown(SwipeEvent event) { executeSwipe(event, engine.getSettings().input().swipeDown()); }

    private void executeSwipe(SwipeEvent event, String action) {
        if (!engine.isOpen()) return;
        executeTapAction(action);
        swipeHandled = true;
        event.consume();
    }

    private void onZoom(ZoomEvent event) {
        if (!engine.isOpen() || !engine.getSettings().input().pinchZoom()) return;
        double factor = event.getZoomFactor();
        if (!Double.isFinite(factor) || factor <= 0) return;
        zoom = Math.max(0.55, Math.min(2.0, zoom * factor));
        applyZoom();
        event.consume();
    }

    void toggleFullscreenFromInput() {
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) {
            stage.setFullScreen(!stage.isFullScreen());
        }
    }

    // ==================== LIFECYCLE / CALLBACKS ====================

    public void closeBook() {
        if (!engine.isOpen()) return;
        autoScrollController.stop();
        longPressTimer.stop();
        engine.close();
        renderer.setResourceRepository(null);
        clear();
        pageHistory.clear();
        paginationController.close();
        clearSelection(false);
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

    public void setOnPositionChanged(Consumer<ReaderPosition> listener) { this.onPositionChanged = listener; }

    public void setOnPageChanged(Runnable listener) { this.onPageChanged = listener; }

    public void setOnBookClosed(Runnable listener) { this.onBookClosed = listener; }

    public void setOnCloseRequested(Runnable listener) { this.onCloseRequested = listener; }

    public void setOnPageNumberChanged(Consumer<Integer> listener) { this.onPageNumberChanged = listener; }

    public void setOnCenterTap(Runnable listener) { this.onCenterTap = listener; }

    public void setOnToggleToolbarRequested(Runnable listener) { this.onToggleToolbarRequested = listener; }

    public void setOnSearchRequested(Runnable listener) { this.onSearchRequested = listener; }

    public void setOnSettingsChanged(Consumer<ReaderSettings> listener) { this.onSettingsChanged = listener; }

    private void notifySettingsChanged() {
        if (onSettingsChanged != null) {
            onSettingsChanged.accept(engine.getSettings());
        }
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
        longPressTimer.stop();
        if (engine.isOpen()) engine.close();
        renderer.setResourceRepository(null);
        renderer.clear();
        renderer.clearFontCache();
        renderer.clearImageCache();
        pageHistory.clear();
        paginationController.close();
        sizeUpdated = false;
    }
}
