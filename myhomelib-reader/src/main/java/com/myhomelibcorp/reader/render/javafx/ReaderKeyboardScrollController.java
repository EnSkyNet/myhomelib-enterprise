package com.myhomelibcorp.reader.render.javafx;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;

/** Keyboard and wheel/touchpad routing kept out of the rendering viewport. */
final class ReaderKeyboardScrollController {
    private static final long WHEEL_DEBOUNCE_NANOS = 160_000_000L;
    private static final double WHEEL_PAGE_THRESHOLD = 35.0;

    private final ReaderCanvas host;
    private double accumulatedScroll;
    private long lastWheelPageNanos;

    ReaderKeyboardScrollController(ReaderCanvas host) {
        this.host = host;
    }

    void onKeyPressed(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == KeyCode.C && event.isControlDown()) {
            event.consume();
            host.copySelectionFromInput();
        } else if (code == KeyCode.PAGE_DOWN || code == KeyCode.RIGHT || code == KeyCode.SPACE) {
            event.consume();
            if (event.isShiftDown()) host.previousPage(); else host.nextPage();
        } else if (code == KeyCode.PAGE_UP || code == KeyCode.LEFT) {
            event.consume();
            host.previousPage();
        } else if (code == KeyCode.HOME) {
            event.consume();
            host.goToPercent(0);
        } else if (code == KeyCode.END) {
            event.consume();
            host.goToPercent(100);
        } else if (code == KeyCode.UP) {
            event.consume();
            host.previousChapter();
        } else if (code == KeyCode.DOWN) {
            event.consume();
            host.nextChapter();
        } else if (code == KeyCode.P) {
            event.consume();
            host.toggleTwoPageMode();
            host.notifyPageChangedFromInput();
        } else if (code == KeyCode.A) {
            event.consume();
            host.toggleAutoScroll();
            host.notifyPageChangedFromInput();
        } else if ((code == KeyCode.ADD || code == KeyCode.PLUS) && event.isControlDown()) {
            event.consume();
            host.zoomIn();
        } else if ((code == KeyCode.SUBTRACT || code == KeyCode.MINUS) && event.isControlDown()) {
            event.consume();
            host.zoomOut();
        } else if (code == KeyCode.DIGIT0 && event.isControlDown()) {
            event.consume();
            host.resetZoom();
        } else if (code == KeyCode.T) {
            event.consume();
            host.cycleTheme();
        } else if (code == KeyCode.F && event.isControlDown()) {
            event.consume();
            host.requestSearchFromInput();
        } else if (code == KeyCode.F11) {
            event.consume();
            host.toggleFullscreenFromInput();
        } else if (code == KeyCode.ESCAPE) {
            event.consume();
            host.handleEscapeFromInput();
        }
    }

    void onScroll(ScrollEvent event) {
        if (event.isControlDown()) {
            if (event.getDeltaY() > 0) host.zoomIn();
            else if (event.getDeltaY() < 0) host.zoomOut();
            event.consume();
            return;
        }

        accumulatedScroll += event.getDeltaY();
        long now = System.nanoTime();
        boolean enoughDelta = Math.abs(accumulatedScroll) >= WHEEL_PAGE_THRESHOLD;
        boolean debouncePassed = now - lastWheelPageNanos >= WHEEL_DEBOUNCE_NANOS;
        if (enoughDelta && debouncePassed) {
            if (accumulatedScroll > 0) host.previousPage();
            else host.nextPage();
            accumulatedScroll = 0;
            lastWheelPageNanos = now;
        }
        event.consume();
    }
}
