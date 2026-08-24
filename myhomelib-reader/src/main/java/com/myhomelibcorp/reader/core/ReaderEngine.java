package com.myhomelibcorp.reader.core;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookFormatRegistry;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderTheme;
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
 * UI-незалежний стан та навігація reader-а.
 * Рендер відбувається тільки на явний виклик renderPage(...), тому навігація
 * не породжує подвійного малювання.
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
        this.settings = settings != null ? settings : ReaderSettings.defaultSettings();
    }

    public void open(BookSource source) throws IOException {
        if (source == null) {
            throw new IOException("Джерело книги не задано");
        }
        if (isOpen) {
            close();
        }

        log.info("📖 Відкриття книги: {}", source.name());

        BookFormat format = formatRegistry.findFormat(source)
                .orElseThrow(() -> new IOException("Непідтримуваний формат: " + source.extension()));

        BookParser parser = format.createParser();
        currentDocument = parser.parse(source, ParseOptions.defaultOptions());

        if (currentDocument == null || currentDocument.isEmpty()) {
            currentDocument = null;
            throw new IOException("Не вдалося розпарсити книгу або в ній немає тексту");
        }

        currentDocumentId = source.id();
        isOpen = true;
        currentPosition = positionManager.loadPosition(currentDocumentId)
                .orElse(ReaderPosition.start());
        currentPosition = positionManager.validatePosition(currentDocument, currentPosition);

        pageCache.clear();
        imageCache.clear();
        currentDimensions = null;

        log.info("✅ Книгу відкрито: '{}' ({} символів, {} розділів)",
                currentDocument.metadata().title(),
                currentDocument.totalTextLength(),
                currentDocument.chapters().size());
    }

    public void close() {
        if (!isOpen) {
            return;
        }

        if (currentDocumentId != null && currentPosition != null) {
            positionManager.savePosition(currentDocumentId, currentPosition);
        }

        pageCache.clear();
        imageCache.clear();
        if (renderer != null) {
            renderer.clear();
        }

        if (currentDocument != null && currentDocument.resources() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Resource cleanup failed: {}", e.getMessage());
            }
        }

        currentDocument = null;
        currentPosition = null;
        currentDocumentId = null;
        currentDimensions = null;
        isOpen = false;
    }

    public void goToPosition(ReaderPosition position) {
        if (!isOpen || currentDocument == null || position == null) {
            return;
        }

        ReaderPosition validated = positionManager.validatePosition(currentDocument, position);
        this.currentPosition = new ReaderPosition(
                currentDocument.chapterIndexAt(validated.textOffset()),
                validated.textOffset(),
                validated.paragraphIndex(),
                validated.charOffset()
        );

        log.debug("📌 Позиція: offset={}, chapter={}",
                currentPosition.textOffset(), currentPosition.chapterIndex());
    }

    public void goToPercent(double percent) {
        if (!isOpen || currentDocument == null) {
            return;
        }

        long total = currentDocument.totalTextLength();
        if (total <= 0) {
            return;
        }
        double safePercent = Math.max(0, Math.min(100, percent));
        long offset = safePercent >= 100
                ? total - 1
                : (long) (total * safePercent / 100.0);
        goToPosition(new ReaderPosition(currentDocument.chapterIndexAt(offset), offset, 0, 0));
    }

    public void nextPage(PageDimensions dimensions) {
        if (!isOpen || currentDocument == null || dimensions == null || !dimensions.isValid()) {
            return;
        }

        PageLayout current = getCurrentPage(dimensions);
        long currentOffset = currentPosition.textOffset();
        long nextOffset = current.getEndOffset();

        if (nextOffset <= currentOffset) {
            nextOffset = Math.min(currentDocument.totalTextLength(), currentOffset + 1);
        }

        if (nextOffset < currentDocument.totalTextLength()) {
            goToPosition(new ReaderPosition(
                    currentDocument.chapterIndexAt(nextOffset), nextOffset, 0, 0));
        }
    }

    /**
     * Резервний алгоритм для Previous. UI тримає коротку історію реально
     * відвіданих сторінок, а цей метод використовується після довільного jump.
     */
    public void previousPage(PageDimensions dimensions) {
        if (!isOpen || currentDocument == null || dimensions == null || !dimensions.isValid()) {
            return;
        }

        long currentOffset = currentPosition.textOffset();
        if (currentOffset <= 0) {
            return;
        }

        int estimate = Math.max(200, estimateCharsPerPage(dimensions));

        // Шукаємо найбільший startOffset, сторінка з якого закінчується НЕ
        // пізніше currentOffset. Це дає повноцінну попередню сторінку навіть
        // після jump зі змісту/пошуку, не створюючи глобальну page-map.
        long low = Math.max(0, currentOffset - (long) estimate * 3L);
        long high = currentOffset - 1;

        // Якщо оцінка виявилася занадто оптимістичною (великий шрифт, багато
        // заголовків/віршів), розширюємо вікно назад геометрично.
        long expansion = (long) estimate * 3L;
        while (low > 0 && pageEndAt(low, dimensions) > currentOffset) {
            expansion = Math.min(currentOffset, Math.max(expansion + 1, expansion * 2L));
            low = Math.max(0, currentOffset - expansion);
        }

        if (low == 0 && pageEndAt(0, dimensions) >= currentOffset) {
            goToPosition(ReaderPosition.start());
            return;
        }

        long bestStart = Math.max(0, low);
        long bestEnd = -1;
        int iterations = 0;
        while (low <= high && iterations++ < 32) {
            long mid = low + ((high - low) >>> 1);
            long end = pageEndAt(mid, dimensions);

            if (end <= currentOffset) {
                if (end > bestEnd || (end == bestEnd && mid > bestStart)) {
                    bestStart = mid;
                    bestEnd = end;
                }
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        // Невелике локальне уточнення компенсує неідеальну монотонність через
        // пробіли/межі абзаців. Кількість перевірок стала й мала.
        long refineFrom = Math.max(0, bestStart - 8);
        long refineTo = Math.min(currentOffset - 1, bestStart + 8);
        for (long probe = refineFrom; probe <= refineTo; probe++) {
            long end = pageEndAt(probe, dimensions);
            if (end <= currentOffset && (end > bestEnd || (end == bestEnd && probe > bestStart))) {
                bestStart = probe;
                bestEnd = end;
            }
        }

        goToPosition(new ReaderPosition(
                currentDocument.chapterIndexAt(bestStart), bestStart, 0, 0));
    }

    private long pageEndAt(long offset, PageDimensions dimensions) {
        long safeOffset = Math.max(0, Math.min(offset, currentDocument.totalTextLength() - 1));
        ReaderPosition probePosition = new ReaderPosition(
                currentDocument.chapterIndexAt(safeOffset), safeOffset, 0, 0);
        PageLayout page = pageCache.getOrCompute(currentDocument, probePosition, dimensions, layoutEngine);
        return page != null ? page.getEndOffset() : safeOffset;
    }

    public void nextChapter() {
        if (!isOpen || currentDocument == null || currentDocument.chapters().isEmpty()) {
            return;
        }
        int currentChapter = currentDocument.chapterIndexAt(currentPosition.textOffset());
        if (currentChapter < currentDocument.chapters().size() - 1) {
            ChapterIndex next = currentDocument.chapter(currentChapter + 1);
            if (next != null) {
                goToPosition(new ReaderPosition(currentChapter + 1, next.startOffset(), 0, 0));
            }
        }
    }

    public void previousChapter() {
        if (!isOpen || currentDocument == null || currentDocument.chapters().isEmpty()) {
            return;
        }
        int currentChapter = currentDocument.chapterIndexAt(currentPosition.textOffset());
        if (currentChapter > 0) {
            ChapterIndex prev = currentDocument.chapter(currentChapter - 1);
            if (prev != null) {
                goToPosition(new ReaderPosition(currentChapter - 1, prev.startOffset(), 0, 0));
            }
        } else {
            goToPosition(ReaderPosition.start());
        }
    }

    public void goToStart() {
        goToPosition(ReaderPosition.start());
    }

    public void goToEnd() {
        if (!isOpen || currentDocument == null || currentDocument.totalTextLength() <= 0) {
            return;
        }
        long offset = currentDocument.totalTextLength() - 1;
        goToPosition(new ReaderPosition(currentDocument.chapterIndexAt(offset), offset, 0, 0));
    }

    public void renderPage(PageDimensions dimensions) {
        if (dimensions == null || !dimensions.isValid()) {
            return;
        }
        this.currentDimensions = dimensions;
        renderPage();
    }

    public void renderPage() {
        if (!isOpen || currentDocument == null || renderer == null) {
            return;
        }
        if (currentDimensions == null || !currentDimensions.isValid()) {
            log.debug("Canvas ще не отримав дійсних розмірів");
            return;
        }

        PageLayout page = getCurrentPage(currentDimensions);
        ReaderTheme theme = ReaderTheme.fromName(settings.themeName());
        if (page != null && !page.isEmpty()) {
            renderer.renderPage(page, null, theme);
        } else {
            renderer.clear();
            log.warn("⚠️ Layout повернув порожню сторінку для offset={}", currentPosition.textOffset());
        }
    }

    public PageLayout getCurrentPage(PageDimensions dimensions) {
        if (!isOpen || currentDocument == null || dimensions == null || !dimensions.isValid()) {
            return PageLayout.empty();
        }
        this.currentDimensions = dimensions;
        return pageCache.getOrCompute(currentDocument, currentPosition, dimensions, layoutEngine);
    }

    public void applySettings(ReaderSettings newSettings) {
        if (newSettings == null) {
            return;
        }
        this.settings = newSettings;
        layoutEngine.updateSettings(newSettings);
        layoutEngine.clearCache();
        pageCache.clear();
        log.debug("⚙️ Reader settings: font={} {}, theme={}",
                newSettings.fontFamily(), newSettings.fontSize(), newSettings.themeName());
    }

    private int estimateCharsPerPage(PageDimensions dimensions) {
        if (!dimensions.isValid()) {
            return 2000;
        }
        double avgCharWidth = Math.max(4.0, settings.fontSize() * 0.52);
        double charsPerLine = dimensions.getContentWidth() / avgCharWidth;
        double lineHeight = Math.max(8.0, settings.fontSize() * 1.18 * settings.lineSpacing());
        int linesPerPage = Math.max(1, (int) (dimensions.getContentHeight() / lineHeight));
        return Math.max(100, (int) (charsPerLine * linesPerPage * 0.85));
    }

    public double getProgressPercent() {
        if (!isOpen || currentDocument == null || currentPosition == null) {
            return 0.0;
        }
        return currentPosition.getPercent(currentDocument.totalTextLength());
    }

    public int getCurrentChapterIndex() {
        if (!isOpen || currentDocument == null || currentPosition == null) {
            return 0;
        }
        return currentDocument.chapterIndexAt(currentPosition.textOffset());
    }

    public String getCurrentChapterTitle() {
        if (!isOpen || currentDocument == null || currentPosition == null) {
            return "";
        }
        ChapterIndex chapter = currentDocument.chapter(getCurrentChapterIndex());
        return chapter != null ? chapter.title() : "";
    }

    public boolean isOpen() {
        return isOpen;
    }

    public String getCacheStats() {
        return "PageCache: " + pageCache.size() + "/" + pageCache.getMaxSize() +
                ", " + layoutEngine.getCacheStats() +
                ", ImageCache: " + imageCache.getStats();
    }

    public void clearCaches() {
        pageCache.clear();
        imageCache.clear();
        layoutEngine.clearCache();
    }

    public BookFormatRegistry getFormatRegistry() {
        return formatRegistry;
    }

    public TextLayoutEngine getLayoutEngine() {
        return layoutEngine;
    }
}
