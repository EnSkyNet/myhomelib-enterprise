package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy helper for clients that still pre-build a list of PageLayout objects.
 * ReaderCanvas itself intentionally uses streaming/on-demand pagination instead,
 * so a whole-book page map is not kept in RAM.
 */
@Slf4j
public class PageModeController {

    private final Canvas canvas;
    private final GraphicsContext gc;
    private boolean pageModeEnabled;
    private int currentPage = 1; // public API is 1-based
    private int totalPages = 1;
    private List<PageLayout> pages = List.of();

    public PageModeController(Canvas canvas) {
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
    }

    public boolean isPageModeEnabled() {
        return pageModeEnabled;
    }

    public void setPageModeEnabled(boolean enabled) {
        this.pageModeEnabled = enabled;
        log.debug("Page mode: {}", enabled ? "on" : "off");
        if (enabled && !pages.isEmpty()) {
            renderPage(currentPage);
        }
    }

    public void setPages(List<PageLayout> pages) {
        this.pages = pages == null ? List.of() : new ArrayList<>(pages);
        this.totalPages = Math.max(1, this.pages.size());
        this.currentPage = 1;
        if (pageModeEnabled && !this.pages.isEmpty()) {
            renderPage(currentPage);
        }
    }

    public void nextPage() {
        if (!pageModeEnabled || currentPage >= totalPages || pages.isEmpty()) return;
        renderPage(++currentPage);
    }

    public void previousPage() {
        if (!pageModeEnabled || currentPage <= 1 || pages.isEmpty()) return;
        renderPage(--currentPage);
    }

    public void goToPage(int page) {
        if (!pageModeEnabled || page < 1 || page > totalPages || pages.isEmpty()) return;
        currentPage = page;
        renderPage(currentPage);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getTotalPages() {
        return totalPages;
    }

    private void renderPage(int oneBasedPage) {
        int index = oneBasedPage - 1;
        if (index < 0 || index >= pages.size()) return;

        PageLayout page = pages.get(index);
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setTextBaseline(VPos.TOP);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.BLACK);

        for (LineLayout line : page.getLines()) {
            if (line.text() == null || line.text().isEmpty() || line.text().startsWith("[IMAGE:")) {
                continue;
            }
            gc.setFont(fontFor(line));
            gc.fillText(line.text(), line.x(), line.y());
        }

        gc.setFill(Color.GRAY);
        gc.setFont(Font.font(12));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(oneBasedPage + " / " + totalPages, canvas.getWidth() / 2.0, Math.max(0, canvas.getHeight() - 20));
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private Font fontFor(LineLayout line) {
        TextStyle style = line.style() == null ? TextStyle.NORMAL : line.style();
        FontWeight weight = switch (style) {
            case BOLD, BOLD_ITALIC, STRONG, HEADING_1, HEADING_2, HEADING_3, HEADING_4, HEADING_5, HEADING_6 -> FontWeight.BOLD;
            default -> FontWeight.NORMAL;
        };
        FontPosture posture = switch (style) {
            case ITALIC, BOLD_ITALIC, EMPHASIS, CITE, EPIGRAPH -> FontPosture.ITALIC;
            default -> FontPosture.REGULAR;
        };
        return Font.font("Georgia", weight, posture, Math.max(1, line.fontSize()));
    }

    public void toggle() {
        setPageModeEnabled(!pageModeEnabled);
    }
}
