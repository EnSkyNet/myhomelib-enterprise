package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.parser.JsoupFb2Parser;
import com.myhomelibcorp.reader.renderer.DocumentToHtmlConverter;
import com.myhomelibcorp.reader.session.ReaderSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderContentService {

    private final ReaderSettingsService settingsService;
    private final ReaderScheduler scheduler;
    private final JsoupFb2Parser fb2Parser = new JsoupFb2Parser();
    private final DocumentToHtmlConverter htmlConverter = new DocumentToHtmlConverter();

    private final ConcurrentMap<String, ReaderBookContent> contentCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<ReaderBookContent>> loadingTasks = new ConcurrentHashMap<>();

    private static final int MAX_CACHED_HTML_BYTES = 64 * 1024 * 1024;
    private static final int MAX_CACHED_ITEMS = 5;

    private static final Charset[] ZIP_CHARSETS = {
            Charset.forName("IBM866"),
            Charset.forName("Windows-1251"),
            Charset.forName("UTF-8"),
            Charset.forName("KOI8-R"),
            Charset.forName("ISO-8859-5")
    };

    public void loadBookContent(ReaderSession session, Runnable onLoaded) {
        if (session == null || session.getBook() == null) {
            if (onLoaded != null) {
                scheduler.runOnFxThread(onLoaded);
            }
            return;
        }

        BookDto book = session.getBook();
        String bookId = book.getId();

        ReaderBookContent cached = contentCache.get(bookId);
        if (cached != null) {
            log.info("Loading book from cache: {}", book.getTitle());
            session.setChapters(cached.chapters());
            renderHtml(session, cached.html(), onLoaded);
            return;
        }

        CompletableFuture<ReaderBookContent> existingTask = loadingTasks.get(bookId);
        if (existingTask != null && !existingTask.isDone()) {
            log.info("Book loading already in progress: {}", book.getTitle());
            existingTask.thenAccept(content -> {
                if (session.isActive()) {
                    session.setChapters(content.chapters());
                    renderHtml(session, content.html(), onLoaded);
                }
            });
            return;
        }

        log.info("Loading book content asynchronously: {}", book.getTitle());

        scheduler.runOnFxThread(() -> {
            if (session.getWebEngine() != null) {
                session.getWebEngine().loadContent("");
            }
        });

        Executor executor = runnable -> scheduler.execute(runnable);

        CompletableFuture<ReaderBookContent> task = CompletableFuture.supplyAsync(() -> {
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
        }, executor);

        loadingTasks.put(bookId, task);

        task.thenAccept(content -> {
            if (session.isActive()) {
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

    private void cacheContent(String bookId, ReaderBookContent content) {
        int htmlSize = content.html().getBytes().length;
        if (htmlSize > MAX_CACHED_HTML_BYTES) {
            log.debug("Book HTML too large ({} MB), not caching", htmlSize / 1024 / 1024);
            return;
        }

        if (contentCache.size() >= MAX_CACHED_ITEMS) {
            String oldestKey = contentCache.keySet().iterator().next();
            contentCache.remove(oldestKey);
            log.debug("Removed oldest cache entry: {}", oldestKey);
        }

        contentCache.put(bookId, content);
        log.debug("Cached book: {}", bookId);
    }

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

        // Додаємо listener для виправлення скролу після завантаження
        engine.getLoadWorker().stateProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends javafx.concurrent.Worker.State> obs,
                                javafx.concurrent.Worker.State oldState,
                                javafx.concurrent.Worker.State newState) {
                if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                    engine.getLoadWorker().stateProperty().removeListener(this);
                    scheduler.runOnFxThread(() -> {
                        // Виправляємо перекриття скролу через JavaScript
                        fixScrollbarOverlap(engine);

                        if (onLoaded != null) {
                            onLoaded.run();
                        }
                    });
                }
            }
        });

        engine.loadContent(fullHtml);
        log.info("HTML rendered for book: {}", session.getBook().getTitle());
    }

    /**
     * Виправляє перекриття тексту скролом через JavaScript.
     */
    private void fixScrollbarOverlap(javafx.scene.web.WebEngine engine) {
        if (engine == null) {
            return;
        }

        try {
            String script = """
                (function() {
                    // Перевіряємо чи є скрол
                    var hasScroll = document.documentElement.scrollHeight > document.documentElement.clientHeight;
                    
                    if (!hasScroll) {
                        document.body.style.paddingRight = '0px';
                        return;
                    }
                    
                    // Отримуємо ширину скролу
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

    private String injectStyles(String html, String css) {
        if (html.contains("<head>")) {
            return html.replace("</head>", "<style id='reader-styles'>" + css + "</style></head>");
        } else if (html.contains("<body>")) {
            return html.replace("<body>", "<body><style id='reader-styles'>" + css + "</style>");
        }
        return "<!DOCTYPE html><html><head><style id='reader-styles'>" + css + "</style></head><body>" + html + "</body></html>";
    }

    private String createErrorHtml(BookDto book, String error) {
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
                """.formatted(
                book.getTitle() != null ? book.getTitle() : "Без назви",
                book.getAuthorsText() != null ? book.getAuthorsText() : "Невідомий автор",
                error
        );
    }

    // ==================== Читання даних книги ====================

    private byte[] readBookData(BookDto book) throws Exception {
        try {
            String fileName = book.getFileName();
            String folder = book.getFolder();
            String root = book.getCollectionRoot();
            String archiveEntry = book.getArchiveEntry();

            log.debug("readBookData: fileName={}, folder={}, root={}, archiveEntry={}",
                    fileName, folder, root, archiveEntry);

            if (archiveEntry != null && !archiveEntry.isBlank()) {
                Path archivePath = findArchivePath(book);
                if (archivePath != null && Files.exists(archivePath)) {
                    return readFromArchive(archivePath, archiveEntry, fileName);
                }
            }

            if (fileName != null && isArchive(fileName)) {
                Path archivePath = buildFilePath(root, folder, fileName);
                if (archivePath != null && Files.exists(archivePath)) {
                    return readFromArchive(archivePath, null, fileName);
                }
            }

            if (folder != null && isArchive(folder)) {
                Path archivePath = buildFilePath(root, null, folder);
                if (archivePath != null && Files.exists(archivePath)) {
                    return readFromArchive(archivePath, null, fileName);
                }
            }

            Path bookPath = buildFilePath(root, folder, fileName);
            if (bookPath != null && Files.exists(bookPath) && !isArchive(bookPath.toString())) {
                return Files.readAllBytes(bookPath);
            }

            log.warn("File not found: {}", bookPath);
            return null;
        } catch (Exception e) {
            log.error("Failed to read book data for: {}", book.getTitle(), e);
            throw new Exception("Failed to read book: " + e.getMessage(), e);
        }
    }

    private Path findArchivePath(BookDto book) {
        String folder = book.getFolder();
        String fileName = book.getFileName();
        String root = book.getCollectionRoot();

        if (folder != null && !folder.isBlank() && isArchive(folder)) {
            return buildFilePath(root, null, folder);
        }

        if (fileName != null && !fileName.isBlank() && isArchive(fileName)) {
            return buildFilePath(root, folder, fileName);
        }

        if (folder != null && !folder.isBlank() && fileName != null && !fileName.isBlank()) {
            Path combined = buildFilePath(root, folder, fileName);
            if (combined != null && Files.exists(combined) && isArchive(combined.toString())) {
                return combined;
            }
        }

        return null;
    }

    private Path buildFilePath(String root, String folder, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        Path filePath = Paths.get(fileName);
        if (filePath.isAbsolute()) {
            return filePath;
        }

        if (folder != null && !folder.isBlank()) {
            Path folderPath = Paths.get(folder);
            if (folderPath.isAbsolute()) {
                return folderPath.resolve(fileName);
            }
            if (root != null && !root.isBlank()) {
                return Paths.get(root).resolve(folderPath).resolve(fileName);
            }
            return folderPath.resolve(fileName);
        }

        if (root != null && !root.isBlank()) {
            return Paths.get(root).resolve(fileName);
        }

        return filePath;
    }

    private boolean isArchive(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".fb2zip") || lower.endsWith(".fbd");
    }

    private byte[] readFromArchive(Path archivePath, String entryName, String fileName) throws Exception {
        Exception lastException = null;

        for (Charset charset : ZIP_CHARSETS) {
            try (ZipFile zip = new ZipFile(archivePath.toFile(), charset)) {
                byte[] result = tryReadFromZip(zip, entryName, fileName);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                lastException = e;
            }
        }

        try (ZipFile zip = new ZipFile(archivePath.toFile())) {
            byte[] result = tryReadFromZip(zip, entryName, fileName);
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            lastException = e;
        }

        log.warn("No FB2 entry found in archive: {}", archivePath);
        if (lastException != null) {
            throw new Exception("Failed to read ZIP archive: " + lastException.getMessage(), lastException);
        }
        return null;
    }

    private byte[] tryReadFromZip(ZipFile zip, String entryName, String fileName) throws Exception {
        ZipEntry targetEntry = null;

        if (entryName != null && !entryName.isBlank()) {
            targetEntry = zip.getEntry(entryName);
            if (targetEntry != null) {
                return zip.getInputStream(targetEntry).readAllBytes();
            }
        }

        if (targetEntry == null && fileName != null && !fileName.isBlank()) {
            String searchName = Paths.get(fileName).getFileName().toString();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryFileName = Paths.get(entry.getName()).getFileName().toString();

                if (entryFileName.equals(searchName) ||
                        entry.getName().endsWith(fileName) ||
                        entry.getName().equals(fileName) ||
                        entryFileName.equalsIgnoreCase(searchName)) {
                    targetEntry = entry;
                    return zip.getInputStream(targetEntry).readAllBytes();
                }
            }
        }

        if (targetEntry == null) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".fb2") || name.endsWith(".fbd")) {
                    targetEntry = entry;
                    return zip.getInputStream(targetEntry).readAllBytes();
                }
            }
        }

        return null;
    }

    // ==================== Публічні методи ====================

    public List<Chapter> getChapters(ReaderSession session) {
        if (session == null) {
            return List.of();
        }
        if (session.getChapters() != null && !session.getChapters().isEmpty()) {
            return session.getChapters();
        }
        String bookId = session.getBookId();
        if (bookId != null) {
            ReaderBookContent cached = contentCache.get(bookId);
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
            String escapedCss = css
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");

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

            // Після застосування стилів - виправляємо скрол
            fixScrollbarOverlap(session.getWebEngine());

            log.debug("Settings applied to current book");

        } catch (Exception e) {
            log.warn("Failed to apply settings: {}", e.getMessage());
        }
    }

    public void clearCache() {
        contentCache.clear();
        loadingTasks.clear();
        log.info("Reader cache cleared");
    }
}