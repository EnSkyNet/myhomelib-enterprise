// myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/PageModeController.java
package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.model.PageLayout;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class PageModeController {

    private final Canvas canvas;
    private final GraphicsContext gc;
    private boolean pageModeEnabled = false;
    private int currentPage = 1;
    private int totalPages = 1;
    private List<PageLayout> pages = new ArrayList<>();

    public PageModeController(Canvas canvas) {
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
    }

    public boolean isPageModeEnabled() {
        return pageModeEnabled;
    }

    public void setPageModeEnabled(boolean enabled) {
        this.pageModeEnabled = enabled;
        if (enabled) {
            log.info("📄 Сторінковий режим увімкнено");
        } else {
            log.info("📄 Сторінковий режим вимкнено");
        }
    }

    public void setPages(List<PageLayout> pages) {
        this.pages = pages;
        this.totalPages = pages.size();
        this.currentPage = 1;
        log.info("📄 Створено {} сторінок", totalPages);
    }

    public void nextPage() {
        if (!pageModeEnabled || currentPage >= totalPages) {
            return;
        }
        currentPage++;
        renderPage(currentPage);
    }

    public void previousPage() {
        if (!pageModeEnabled || currentPage <= 1) {
            return;
        }
        currentPage--;
        renderPage(currentPage);
    }

    public void goToPage(int page) {
        if (!pageModeEnabled || page < 1 || page > totalPages) {
            return;
        }
        currentPage = page;
        renderPage(currentPage);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getTotalPages() {
        return totalPages;
    }

    private void renderPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            return;
        }

        PageLayout page = pages.get(pageIndex);
        // Очищуємо
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Білий фон
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Тут малюємо сторінку
        // TODO: перенести рендеринг сторінки сюди

        // Номер сторінки
        gc.setFill(Color.GRAY);
        gc.fillText(pageIndex + 1 + " / " + totalPages,
                canvas.getWidth() / 2 - 30, canvas.getHeight() - 20);
    }

    public void toggle() {
        setPageModeEnabled(!pageModeEnabled);
    }
}