package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.resource.ReaderBookResourcePort;
import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ReaderBookContent;
import com.myhomelibcorp.reader.parser.JsoupFb2Parser;
import com.myhomelibcorp.reader.renderer.DocumentToHtmlConverter;
import com.myhomelibcorp.reader.session.ReaderSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class ReaderContentService {

    private final ReaderSettingsService settingsService;
    private final ReaderScheduler scheduler;
    private final ImageCacheService imageCache;
    private final ReaderBookResourcePort bookResourcePort;
    private final JsoupFb2Parser fb2Parser = new JsoupFb2Parser();
    private final DocumentToHtmlConverter htmlConverter;

    private final Map<String, ReaderBookContent> contentCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ReaderBookContent> eldest) {
            if (size() > MAX_CACHED_ITEMS) {
                evictEntry(eldest);
                return true;
            }
            return false;
        }
    };

    private final ConcurrentMap<String, CompletableFuture<ReaderBookContent>> loadingTasks = new ConcurrentHashMap<>();

    private static final int MAX_CACHED_ITEMS = 3;
    private static final long MAX_CACHE_BYTES = 64 * 1024 * 1024;
    private long currentCacheSize = 0;

    @Autowired
    public ReaderContentService(ReaderSettingsService settingsService,
                                ReaderScheduler scheduler,
                                ImageCacheService imageCache,
                                ReaderBookResourcePort bookResourcePort) {
        this.settingsService = settingsService;
        this.scheduler = scheduler;
        this.imageCache = imageCache;
        this.bookResourcePort = bookResourcePort;
        this.htmlConverter = new DocumentToHtmlConverter(imageCache);
    }

    public void loadBookContent(ReaderSession session, Runnable onLoaded) {
        if (session == null || session.getBook() == null) {
            if (onLoaded != null) {
                scheduler.runOnFxThread(onLoaded);
            }
            return;
        }

        BookDto book = session.getBook();
        String bookId = book.getId();

        ReaderBookContent cached = getCachedContent(bookId);
        if (cached != null && !cached.isEmpty()) {
            log.info("📚 Loading book from cache: {}", book.getTitle());
            session.setChapters(cached.chapters());
            renderHtml(session, cached.html(), onLoaded);
            return;
        }

        log.info("Loading book content asynchronously: {}", book.getTitle());

        scheduler.runOnFxThread(() -> {
            if (session.getWebEngine() != null) {
                session.getWebEngine().loadContent("");
            }
        });

        Executor executor = runnable -> scheduler.execute(runnable);

        CompletableFuture<ReaderBookContent> task = loadingTasks.computeIfAbsent(bookId, id ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        byte[] data = readBookData(book);
                        if (data == null || data.length == 0) {
                            throw new RuntimeException("Failed to read book data");
                        }

                        BookDocument document = fb2Parser.parse(new ByteArrayInputStream(data));
                        String html = htmlConverter.convert(document);

                        ReaderBookContent content = new ReaderBookContent(html, document.getChapters());
                        cacheContent(bookId, content);
                        return content;

                    } catch (Exception e) {
                        log.error("Failed to load book content: {}", book.getTitle(), e);
                        throw new RuntimeException(e);
                    }
                }, executor)
        );

        task.thenAccept(content -> {
            if (session.isActive() && content != null && !content.isEmpty()) {
                session.setChapters(content.chapters());
                renderHtml(session, content.html(), onLoaded);
            }
        }).exceptionally(ex -> {
            log.error("Failed to load book: {}", book.getTitle(), ex);
            scheduler.runOnFxThread(() -> {
                renderError(session, "Помилка завантаження: " + ex.getMessage(), onLoaded);
            });
            return null;
        }).thenRun(() -> {
            loadingTasks.remove(bookId);
        });
    }

    private byte[] readBookData(BookDto book) throws Exception {
        if (book == null) {
            throw new IllegalArgumentException("Book cannot be null");
        }

        try (InputStream is = bookResourcePort
                .readBookData(book)
                .orElseThrow(() -> new RuntimeException("Book file not found: " + book.getFileName()))) {
            return is.readAllBytes();
        }
    }

    private void cacheContent(String bookId, ReaderBookContent content) {
        if (bookId == null || content == null || content.isEmpty()) {
            return;
        }

        long size = content.sizeBytes();

        if (size > MAX_CACHE_BYTES) {
            log.debug("Book HTML too large ({} MB), not caching", size / 1024 / 1024);
            return;
        }

        synchronized (contentCache) {
            while (currentCacheSize + size > MAX_CACHE_BYTES && !contentCache.isEmpty()) {
                String oldestKey = contentCache.keySet().iterator().next();
                ReaderBookContent oldest = contentCache.remove(oldestKey);
                if (oldest != null) {
                    currentCacheSize -= oldest.sizeBytes();
                    log.debug("Evicted cache entry: {} ({} MB)", oldestKey, oldest.sizeBytes() / 1024 / 1024);
                }
            }

            contentCache.put(bookId, content);
            currentCacheSize += size;
            log.debug("Cached book: {}, size: {} MB, cache size: {} MB ({} items)",
                    bookId, size / 1024 / 1024, currentCacheSize / 1024 / 1024, contentCache.size());
        }
    }

    private void evictEntry(Map.Entry<String, ReaderBookContent> eldest) {
        if (eldest != null && eldest.getValue() != null) {
            currentCacheSize -= eldest.getValue().sizeBytes();
            log.debug("Evicted cache entry by LRU: {} ({} MB)",
                    eldest.getKey(), eldest.getValue().sizeBytes() / 1024 / 1024);
        }
    }

    public ReaderBookContent getCachedContent(String bookId) {
        if (bookId == null) {
            return null;
        }
        synchronized (contentCache) {
            return contentCache.get(bookId);
        }
    }

    public boolean isCached(String bookId) {
        if (bookId == null) {
            return false;
        }
        synchronized (contentCache) {
            return contentCache.containsKey(bookId);
        }
    }

    public long getCacheSize() {
        synchronized (contentCache) {
            return currentCacheSize;
        }
    }

    public int getCacheItemCount() {
        synchronized (contentCache) {
            return contentCache.size();
        }
    }

    public String getCacheStats() {
        synchronized (contentCache) {
            return String.format("Items: %d, Size: %.2f MB, Max: %d MB",
                    contentCache.size(),
                    currentCacheSize / 1024.0 / 1024.0,
                    MAX_CACHE_BYTES / 1024 / 1024);
        }
    }

    public void clearCache() {
        synchronized (contentCache) {
            contentCache.clear();
            currentCacheSize = 0;
        }
        loadingTasks.clear();
        log.info("Reader cache cleared");
    }

    // ==================== РЕНДЕРИНГ HTML ====================

    private void renderHtml(ReaderSession session, String html, Runnable onLoaded) {
        scheduler.runOnFxThread(() -> renderHtmlSync(session, html, onLoaded));
    }

    private void renderHtmlSync(ReaderSession session, String html, Runnable onLoaded) {
        if (session == null || session.getWebEngine() == null) {
            if (onLoaded != null) {
                onLoaded.run();
            }
            return;
        }

        String css = settingsService.generateCss();
        String fullHtml = injectStyles(html, css);

        if (session.getWebView() != null) {
            session.getWebView().setVisible(true);
        }

        var engine = session.getWebEngine();

        // ВИДАЛЯЄМО ВСІ ЛОГИ КРІМ ЦЬОГО
        log.info("📄 Loading HTML into WebEngine...");

        engine.getLoadWorker().stateProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends javafx.concurrent.Worker.State> obs,
                                javafx.concurrent.Worker.State oldState,
                                javafx.concurrent.Worker.State newState) {
                if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                    engine.getLoadWorker().stateProperty().removeListener(this);
                    scheduler.runOnFxThread(() -> {
                        fixScrollbarOverlap(engine);
                        if (onLoaded != null) {
                            onLoaded.run();
                        }
                    });
                }
            }
        });

        engine.loadContent(fullHtml);
    }

    private void fixScrollbarOverlap(javafx.scene.web.WebEngine engine) {
        if (engine == null) {
            return;
        }

        try {
            String script = """
                (function() {
                    var hasScroll = document.documentElement.scrollHeight > document.documentElement.clientHeight;
                    
                    if (!hasScroll) {
                        document.body.style.paddingRight = '0px';
                        return;
                    }
                    
                    var scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
                    
                    if (scrollbarWidth > 0) {
                        var body = document.body;
                        if (!body) return;
                        
                        var computedStyle = window.getComputedStyle(body);
                        var maxWidth = computedStyle.maxWidth;
                        
                        if (maxWidth === '100%' || maxWidth === 'none') {
                            body.style.paddingRight = scrollbarWidth + 'px';
                            body.style.boxSizing = 'border-box';
                        } else {
                            body.style.paddingRight = '0px';
                        }
                    }
                })();
            """;
            engine.executeScript(script);
        } catch (Exception e) {
            log.debug("Failed to fix scrollbar overlap: {}", e.getMessage());
        }
    }

    private String injectStyles(String html, String css) {
        if (html == null) {
            return "<!DOCTYPE html><html><head><style>" + css + "</style></head><body></body></html>";
        }

        if (html.contains("<head>")) {
            return html.replace("</head>", "<style id='reader-styles'>" + css + "</style></head>");
        } else if (html.contains("<body>")) {
            return html.replace("<body>", "<body><style id='reader-styles'>" + css + "</style>");
        }
        return "<!DOCTYPE html><html><head><style id='reader-styles'>" + css + "</style></head><body>" + html + "</body></html>";
    }

    private void renderError(ReaderSession session, String error, Runnable onLoaded) {
        scheduler.runOnFxThread(() -> renderErrorSync(session, error, onLoaded));
    }

    private void renderErrorSync(ReaderSession session, String error, Runnable onLoaded) {
        if (session == null) {
            if (onLoaded != null) {
                onLoaded.run();
            }
            return;
        }

        String html = createErrorHtml(session.getBook(), error);
        if (session.getWebEngine() != null) {
            session.getWebEngine().loadContent(html);
        }
        log.warn("Error rendered for book: {}", error);

        if (onLoaded != null) {
            onLoaded.run();
        }
    }

    private String createErrorHtml(BookDto book, String error) {
        String title = book != null && book.getTitle() != null ? book.getTitle() : "Без назви";
        String author = book != null && book.getAuthorsText() != null ? book.getAuthorsText() : "Невідомий автор";

        return """
                <html>
                <head><meta charset="UTF-8"/></head>
                <body>
                    <h1>Помилка завантаження книги</h1>
                    <p><b>Назва:</b> %s</p>
                    <p><b>Автор:</b> %s</p>
                    <p><b>Помилка:</b> %s</p>
                </body>
                </html>
                """.formatted(title, author, error);
    }

    public List<Chapter> getChapters(ReaderSession session) {
        if (session == null) {
            return List.of();
        }

        if (session.getChapters() != null && !session.getChapters().isEmpty()) {
            return session.getChapters();
        }

        String bookId = session.getBookId();
        if (bookId != null) {
            ReaderBookContent cached = getCachedContent(bookId);
            if (cached != null) {
                return cached.chapters();
            }
        }

        return List.of();
    }

    public void applySettings(ReaderSession session) {
        scheduler.runOnFxThread(() -> applySettingsSync(session));
    }

    private void applySettingsSync(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return;
        }

        try {
            String css = settingsService.generateCss();
            String escapedCss = escapeCssForJavaScript(css);

            String script = """
                (function() {
                    try {
                        var style = document.getElementById('reader-styles');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = 'reader-styles';
                            document.head.appendChild(style);
                        }
                        style.textContent = CSS;
                    } catch(e) {
                        console.error('Failed to apply styles:', e);
                    }
                })();
            """.replace("CSS", "'" + escapedCss + "'");

            session.getWebEngine().executeScript(script);
            fixScrollbarOverlap(session.getWebEngine());
            log.debug("Settings applied to current book");

        } catch (Exception e) {
            log.warn("Failed to apply settings: {}", e.getMessage());
        }
    }

    private String escapeCssForJavaScript(String css) {
        if (css == null) {
            return "";
        }

        return css
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public void clearImageCache() {
        imageCache.clear();
        log.info("Image cache cleared");
    }

    public ImageCacheService getImageCache() {
        return imageCache;
    }
}