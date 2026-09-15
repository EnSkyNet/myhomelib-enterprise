package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.core.ReaderEngine;
import javafx.animation.PauseTransition;
import javafx.geometry.Point2D;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/** Desktop/touch pointer gestures kept outside ReaderCanvas to keep the viewport focused on rendering. */
final class ReaderPointerGestureController {
    private static final long LONG_PRESS_MS = 520L;
    private static final double MOUSE_SELECTION_THRESHOLD = 5.0;

    private final ReaderCanvas owner;
    private final ReaderEngine engine;
    private final ReaderSelectionController selection;
    private final PauseTransition longPressTimer = new PauseTransition(Duration.millis(LONG_PRESS_MS));

    private boolean dragging;
    private boolean swipeHandled;
    private boolean longPressHandled;
    private boolean selectionGestureHandled;
    private double dragStartX;
    private double dragStartY;

    ReaderPointerGestureController(ReaderCanvas owner, ReaderEngine engine, ReaderSelectionController selection) {
        this.owner = owner;
        this.engine = engine;
        this.selection = selection;
    }

    boolean consumeHandledClick() {
        if (!swipeHandled && !longPressHandled && !selectionGestureHandled) return false;
        swipeHandled = false;
        longPressHandled = false;
        selectionGestureHandled = false;
        return true;
    }

    void markSwipeHandled() {
        longPressTimer.stop();
        dragging = false;
        swipeHandled = true;
    }

    void reset() {
        longPressTimer.stop();
        dragging = false;
        swipeHandled = false;
        longPressHandled = false;
        selectionGestureHandled = false;
    }

    void onPressed(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) return;
        if (event.getClickCount() > 1) owner.cancelTextSingleClick();
        longPressTimer.stop();
        owner.hideSelectionContextMenu();
        // Reset completion flags at the beginning of every pointer gesture as well as on click.
        // JavaFX can suppress MOUSE_CLICKED after a real drag, so carrying a previous flag into the
        // next press would otherwise eat the next legitimate click/tap action.
        swipeHandled = false;
        longPressHandled = false;
        selectionGestureHandled = false;

        if (engine.isOpen() && selection.hasSelection() && owner.ensureDimensions()) {
            ReaderCanvas.SelectionPage handlePage = owner.selectionPageAt(event.getX());
            if (selection.beginHandleDrag(event.getX(), event.getY(), handlePage.page(), handlePage.xOffset())) {
                dragging = false;
                selectionGestureHandled = true;
                owner.render();
                event.consume();
                return;
            }
        }
        if (event.isShiftDown() && engine.isOpen() && owner.ensureDimensions()) {
            dragging = false;
            ReaderCanvas.SelectionPage page = owner.selectionPageAt(event.getX());
            selection.begin(event.getX(), event.getY(), page.page(), page.xOffset());
            selectionGestureHandled = true;
            owner.notifySelectionChanged();
            owner.render();
            event.consume();
            return;
        }

        dragging = true;
        owner.clearSelection(false);
        swipeHandled = false;
        dragStartX = event.getX();
        dragStartY = event.getY();
        longPressTimer.setOnFinished(ignored -> onLongPress());
        longPressTimer.playFromStart();
    }

    private void onLongPress() {
        if (!dragging || !engine.isOpen() || swipeHandled || selection.isSelecting()) return;
        ReaderCanvas.SelectionPage page = owner.selectionPageAt(dragStartX);
        if (selection.selectWord(dragStartX, dragStartY, page.page(), page.xOffset())) {
            selectionGestureHandled = true;
            longPressHandled = true;
            dragging = false;
            owner.notifySelectionChanged();
            owner.render();
            Point2D screen = owner.localToScreen(dragStartX, dragStartY);
            if (screen != null) owner.showSelectionContextMenu(screen.getX(), screen.getY());
        } else {
            owner.executeTapAction(owner.tapActionAt(dragStartX, dragStartY, true));
            longPressHandled = true;
            dragging = false;
        }
    }

    void onDragged(MouseEvent event) {
        owner.cancelTextSingleClick();
        if (selection.isSelecting() && engine.isOpen()) {
            ReaderCanvas.SelectionPage page = owner.selectionPageAt(event.getX());
            selection.drag(event.getX(), event.getY(), page.page(), page.xOffset());
            owner.notifySelectionChanged();
            owner.render();
            event.consume();
            return;
        }
        if (!dragging || !engine.isOpen() || swipeHandled) return;
        double dx = event.getX() - dragStartX;
        double dy = event.getY() - dragStartY;
        if (Math.hypot(dx, dy) < MOUSE_SELECTION_THRESHOLD) return;

        longPressTimer.stop();
        if (!owner.ensureDimensions()) return;
        ReaderCanvas.SelectionPage startPage = owner.selectionPageAt(dragStartX);
        if (!event.isSynthesized()
                && selection.isTextHit(dragStartX, dragStartY, startPage.page(), startPage.xOffset())) {
            selection.begin(dragStartX, dragStartY, startPage.page(), startPage.xOffset());
            ReaderCanvas.SelectionPage currentPage = owner.selectionPageAt(event.getX());
            selection.drag(event.getX(), event.getY(), currentPage.page(), currentPage.xOffset());
            selectionGestureHandled = true;
            owner.notifySelectionChanged();
            owner.render();
            event.consume();
            return;
        }

        if (Math.max(Math.abs(dx), Math.abs(dy)) < 55) return;
        if (Math.abs(dx) > Math.abs(dy) * 1.2) {
            owner.executeTapAction(dx > 0 ? engine.getSettings().input().swipeRight() : engine.getSettings().input().swipeLeft());
        } else if (Math.abs(dy) > Math.abs(dx) * 1.2) {
            owner.executeTapAction(dy > 0 ? engine.getSettings().input().swipeDown() : engine.getSettings().input().swipeUp());
        } else {
            return;
        }
        swipeHandled = true;
        event.consume();
    }

    void onReleased(MouseEvent event) {
        longPressTimer.stop();
        dragging = false;
        if (!selection.isSelecting()) return;
        ReaderCanvas.SelectionPage page = owner.selectionPageAt(event.getX());
        selection.finish(event.getX(), event.getY(), page.page(), page.xOffset());
        selectionGestureHandled = true;
        owner.notifySelectionChanged();
        owner.render();
        if (selection.hasSelection()) owner.showSelectionContextMenu(event.getScreenX(), event.getScreenY());
        event.consume();
        owner.requestFocus();
    }
}
