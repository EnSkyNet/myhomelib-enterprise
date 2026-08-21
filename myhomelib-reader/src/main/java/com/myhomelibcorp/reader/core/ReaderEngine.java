package com.myhomelibcorp.reader.core;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.core.cache.ImageCache;
import com.myhomelibcorp.reader.core.cache.PageCache;
import com.myhomelibcorp.reader.core.position.ReaderPositionManager;
import com.myhomelibcorp.reader.layout.TextLayoutEngine;
import com.myhomelibcorp.reader.model.PageLayout;
import com.myhomelibcorp.reader.render.api.ReaderRenderer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * Основний двигун Reader.
 */
@Slf4j
public class ReaderEngine {

    private final BookFormatRegistry formatRegistry;
    private final TextLayoutEngine layoutEngine;

    @Getter
    @Setter
    private ReaderRenderer renderer;

    private final PageCache pageCache;
    private final ImageCache imageCache;
    private final ReaderPositionManager positionManager;

    @Getter
    private ReaderDocument currentDocument;
    @Getter
    private ReaderPosition currentPosition;
    @Getter
    private ReaderSettings settings;

    private String currentDocumentId;
    private boolean isOpen = false;
    private PageDimensions currentDimensions;

    public ReaderEngine(
            BookFormatRegistry formatRegistry,
            TextLayoutEngine layoutEngine,
            ReaderRenderer renderer,
            PageCache pageCache,
            ImageCache imageCache,
            ReaderPositionManager positionManager,
            ReaderSettings settings
    ) {
        this.formatRegistry = formatRegistry;
        this.layoutEngine = layoutEngine;
        this.renderer = renderer;
        this.pageCache = pageCache;
        this.imageCache = imageCache;
        this.positionManager = positionManager;
        this.settings = settings;
    }

    public void open(BookSource source) throws IOException {
        if (isOpen) {
            close();
        }

        log.info("📖 Відкриття книги: {}", source.name());

        BookFormat format = formatRegistry.findFormat(source)
                .orElseThrow(() -> new IOException("Непідтримуваний формат: " + source.extension()));

        BookParser parser = format.createParser();
        currentDocument = parser.parse(source, ParseOptions.defaultOptions());

        if (currentDocument == null || currentDocument.isEmpty()) {
            throw new IOException("Не вдалося розпарсити книгу");
        }

        currentDocumentId = source.id();
        isOpen = true;

        currentPosition = positionManager.loadPosition(currentDocumentId)
                .orElse(ReaderPosition.start());

        currentPosition = positionManager.validatePosition(currentDocument, currentPosition);

        log.info("✅ Книгу відкрито: '{}' ({} символів, {} розділів)",
                currentDocument.metadata().title(),
                currentDocument.totalTextLength(),
                currentDocument.chapters().size());

        pageCache.clear();
        imageCache.clear();
    }

    public void close() {
        if (!isOpen) {
            return;
        }

        log.info("📖 Закриття книги: {}", currentDocument != null ? currentDocument.metadata().title() : "unknown");

        if (currentDocumentId != null && currentPosition != null) {
            positionManager.savePosition(currentDocumentId, currentPosition);
            log.info("💾 Позицію збережено: offset={}, chapter={}",
                    currentPosition.textOffset(), currentPosition.chapterIndex());
        }

        pageCache.clear();
        imageCache.clear();
        if (renderer != null) {
            renderer.clear();
        }

        currentDocument = null;
        currentPosition = null;
        currentDocumentId = null;
        isOpen = false;

        log.info("✅ Книгу закрито");
    }

    public void goToPosition(ReaderPosition position) {
        if (!isOpen || currentDocument == null) {
            return;
        }

        ReaderPosition validated = positionManager.validatePosition(currentDocument, position);
        this.currentPosition = validated;
        pageCache.clear();
        renderPage();

        log.debug("📌 Перехід до позиції: offset={}, chapter={}",
                validated.textOffset(), validated.chapterIndex());
    }

    public void goToPercent(double percent) {
        if (!isOpen || currentDocument == null) {
            return;
        }

        long total = currentDocument.totalTextLength();
        long offset = (long) (total * Math.max(0, Math.min(100, percent)) / 100.0);
        ReaderPosition pos = new ReaderPosition(
                currentDocument.chapterIndexAt(offset),
                offset,
                0,
                0
        );
        goToPosition(pos);
    }

    public void nextPage(PageDimensions dimensions) {
        if (!isOpen || currentDocument == null) {
            return;
        }

        PageLayout current = getCurrentPage(dimensions);
        long nextOffset = current.getEndOffset();

        if (nextOffset < currentDocument.totalTextLength()) {
            ReaderPosition newPos = new ReaderPosition(
                    currentDocument.chapterIndexAt(nextOffset),
                    nextOffset,
                    0,
                    0
            );
            goToPosition(newPos);
        } else {
            log.debug("📄 Кінець книги");
        }
    }

    public void previousPage(PageDimensions dimensions) {
        if (!isOpen || currentDocument == null) {
            return;
        }

        long currentOffset = currentPosition.textOffset();
        if (currentOffset <= 0) {
            log.debug("📄 Початок книги");
            return;
        }

        long estimatedOffset = Math.max(0, currentOffset - estimateCharsPerPage(dimensions) * 2);
        ReaderPosition newPos = new ReaderPosition(
                currentDocument.chapterIndexAt(estimatedOffset),
                estimatedOffset,
                0,
                0
        );
        goToPosition(newPos);
    }

    public void nextChapter() {
        if (!isOpen || currentDocument == null) {
            return;
        }

        int currentChapter = currentPosition.chapterIndex();
        if (currentChapter < currentDocument.chapters().size() - 1) {
            ChapterIndex next = currentDocument.chapter(currentChapter + 1);
            if (next != null) {
                goToPosition(new ReaderPosition(currentChapter + 1, next.startOffset(), 0, 0));
            }
        }
    }

    public void previousChapter() {
        if (!isOpen || currentDocument == null) {
            return;
        }

        int currentChapter = currentPosition.chapterIndex();
        if (currentChapter > 0) {
            ChapterIndex prev = currentDocument.chapter(currentChapter - 1);
            if (prev != null) {
                goToPosition(new ReaderPosition(currentChapter - 1, prev.startOffset(), 0, 0));
            }
        }
    }

    public void goToStart() {
        goToPosition(ReaderPosition.start());
    }

    public void goToEnd() {
        if (!isOpen || currentDocument == null) {
            return;
        }
        long total = currentDocument.totalTextLength();
        goToPosition(ReaderPosition.end(total));
    }

    public void renderPage() {
        if (!isOpen || currentDocument == null || renderer == null) {
            return;
        }

        if (currentDimensions == null || !currentDimensions.isValid()) {
            log.warn("⚠️ Немає дійсних розмірів сторінки");
            return;
        }

        PageLayout page = getCurrentPage(currentDimensions);
        if (page != null && !page.isEmpty()) {
            ReaderTheme theme = ReaderTheme.fromName(settings.themeName());
            renderer.renderPage(page, null, theme);
            log.trace("🎨 Сторінку відрендерено: {} рядків, {} параграфів",
                    page.getLineCount(), page.getParagraphCount());
        } else {
            log.warn("⚠️ Порожня сторінка");
        }
    }

    public PageLayout getCurrentPage(PageDimensions dimensions) {
        if (!isOpen || currentDocument == null) {
            return PageLayout.empty();
        }

        this.currentDimensions = dimensions;

        return pageCache.getOrCompute(
                currentDocument,
                currentPosition,
                dimensions,
                layoutEngine
        );
    }

    public void applySettings(ReaderSettings newSettings) {
        if (newSettings == null) {
            return;
        }
        this.settings = newSettings;
        pageCache.clear();
        renderPage();
        log.debug("⚙️ Налаштування застосовано: fontSize={}, theme={}",
                newSettings.fontSize(), newSettings.themeName());
    }

    private int estimateCharsPerPage(PageDimensions dimensions) {
        if (!dimensions.isValid()) {
            return 2000;
        }
        double avgCharWidth = settings.fontSize() * 0.5;
        double charsPerLine = dimensions.getContentWidth() / avgCharWidth;
        double lineHeight = settings.fontSize() * settings.lineSpacing();
        int linesPerPage = (int) (dimensions.getContentHeight() / lineHeight);
        return (int) (charsPerLine * linesPerPage * 0.8);
    }

    public double getProgressPercent() {
        if (!isOpen || currentDocument == null) {
            return 0.0;
        }
        return currentPosition.getPercent(currentDocument.totalTextLength());
    }

    public int getCurrentChapterIndex() {
        return currentPosition != null ? currentPosition.chapterIndex() : 0;
    }

    public String getCurrentChapterTitle() {
        if (!isOpen || currentDocument == null) {
            return "";
        }
        ChapterIndex chapter = currentDocument.chapter(currentPosition.chapterIndex());
        return chapter != null ? chapter.title() : "";
    }

    public boolean isOpen() {
        return isOpen;
    }

    public String getCacheStats() {
        return "PageCache: " + pageCache.size() + " pages, " +
                "ImageCache: " + imageCache.getStats();
    }

    public void clearCaches() {
        pageCache.clear();
        imageCache.clear();
        log.debug("🧹 Кеші очищено");
    }

    public BookFormatRegistry getFormatRegistry() {
        return formatRegistry;
    }

    public TextLayoutEngine getLayoutEngine() {
        return layoutEngine;
    }
}