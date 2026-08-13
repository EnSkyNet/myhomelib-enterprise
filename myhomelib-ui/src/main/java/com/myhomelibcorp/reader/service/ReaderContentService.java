package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.reader.model.BookDocument;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderContentService {

    private final ReaderSettingsService settingsService;
    private final JsoupFb2Parser fb2Parser = new JsoupFb2Parser();
    private final DocumentToHtmlConverter htmlConverter = new DocumentToHtmlConverter();

    private final ConcurrentMap<String, String> htmlCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 3;

    private static final Charset[] ZIP_CHARSETS = {
            Charset.forName("IBM866"),
            Charset.forName("Windows-1251"),
            Charset.forName("UTF-8"),
            Charset.forName("KOI8-R"),
            Charset.forName("ISO-8859-5")
    };

    public void loadBookContent(ReaderSession session) {
        if (session == null || session.getBook() == null || session.getWebEngine() == null) {
            return;
        }

        BookDto book = session.getBook();
        String bookId = book.getId();

        String cachedHtml = htmlCache.get(bookId);
        if (cachedHtml != null) {
            log.info("Loading book from cache: {}", book.getTitle());
            renderHtml(session, cachedHtml);
            return;
        }

        log.info("Loading book content: {}", book.getTitle());

        try {
            byte[] data = readBookData(book);
            if (data == null || data.length == 0) {
                renderError(session, "Не вдалося прочитати книгу");
                return;
            }

            BookDocument document = fb2Parser.parse(new ByteArrayInputStream(data));
            String html = htmlConverter.convert(document);
            cacheHtml(bookId, html);
            renderHtml(session, html);

        } catch (Exception e) {
            log.error("Failed to load book content: {}", book.getTitle(), e);
            renderError(session, "Помилка завантаження: " + e.getMessage());
        }
    }

    public void applySettings(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return;
        }

        String css = settingsService.generateCss();
        String script = """
            (function() {
                var style = document.getElementById('reader-styles');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'reader-styles';
                    document.head.appendChild(style);
                }
                style.textContent = CSS;
            })();
        """.replace("CSS", css.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"));

        try {
            session.getWebEngine().executeScript(script);
            log.debug("Settings applied to current book");
        } catch (Exception e) {
            log.warn("Failed to apply settings: {}", e.getMessage());
        }
    }

    private void renderHtml(ReaderSession session, String html) {
        if (session == null || session.getWebEngine() == null) {
            return;
        }

        String css = settingsService.generateCss();
        String fullHtml = injectStyles(html, css);

        session.setCurrentHtml(fullHtml);
        session.setLastLoadedHtml(fullHtml);
        session.setContentLoaded(false);

        session.getWebEngine().loadContent(fullHtml);
        log.info("HTML rendered for book: {}", session.getBook().getTitle());
    }

    private void renderError(ReaderSession session, String error) {
        if (session == null || session.getWebEngine() == null) {
            return;
        }

        String html = createErrorHtml(session.getBook(), error);
        session.getWebEngine().loadContent(html);
        log.warn("Error rendered for book: {}", error);
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
                    <hr/>
                    <p style="color: #888; font-size: 12px;">
                        Перевірте, чи файл існує та чи не пошкоджений.
                    </p>
                </body>
                </html>
                """.formatted(
                book.getTitle() != null ? book.getTitle() : "Без назви",
                book.getAuthorsText() != null ? book.getAuthorsText() : "Невідомий автор",
                error
        );
    }

    private void cacheHtml(String bookId, String html) {
        if (htmlCache.size() >= MAX_CACHE_SIZE) {
            String oldest = htmlCache.keySet().iterator().next();
            htmlCache.remove(oldest);
            log.debug("Removed oldest cache entry: {}", oldest);
        }
        htmlCache.put(bookId, html);
    }

    public void clearCache() {
        htmlCache.clear();
        log.info("Reader cache cleared");
    }

    // ==================== Робота з файлами ====================

    private byte[] readBookData(BookDto book) throws Exception {
        String fileName = book.getFileName();
        String folder = book.getFolder();
        String root = book.getCollectionRoot();
        String archiveEntry = book.getArchiveEntry();

        log.debug("readBookData: fileName={}, folder={}, root={}, archiveEntry={}",
                fileName, folder, root, archiveEntry);

        // 1. Якщо є archiveEntry і folder вказує на ZIP
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            Path archivePath = findArchivePath(book);
            if (archivePath != null && Files.exists(archivePath)) {
                log.debug("Reading from archive: {}, entry: {}", archivePath, archiveEntry);
                return readFromArchive(archivePath, archiveEntry, fileName);
            }
        }

        // 2. Якщо fileName вказує на ZIP + FB2 всередині
        if (fileName != null && isArchive(fileName)) {
            Path archivePath = buildFilePath(root, folder, fileName);
            if (archivePath != null && Files.exists(archivePath)) {
                log.debug("Reading from archive (fileName is archive): {}", archivePath);
                return readFromArchive(archivePath, null, fileName);
            }
        }

        // 3. Якщо folder вказує на ZIP
        if (folder != null && isArchive(folder)) {
            Path archivePath = buildFilePath(root, null, folder);
            if (archivePath != null && Files.exists(archivePath)) {
                log.debug("Reading from archive (folder is archive): {}", archivePath);
                return readFromArchive(archivePath, null, fileName);
            }
        }

        // 4. Звичайний FB2 файл
        Path bookPath = buildFilePath(root, folder, fileName);
        if (bookPath != null && Files.exists(bookPath) && !isArchive(bookPath.toString())) {
            log.debug("Reading regular file: {}", bookPath);
            return Files.readAllBytes(bookPath);
        }

        log.warn("File not found: {}", bookPath);
        return null;
    }

    private Path findArchivePath(BookDto book) {
        String folder = book.getFolder();
        String fileName = book.getFileName();
        String root = book.getCollectionRoot();

        // Перевіряємо, чи folder вказує на ZIP
        if (folder != null && !folder.isBlank() && isArchive(folder)) {
            return buildFilePath(root, null, folder);
        }

        // Перевіряємо, чи fileName вказує на ZIP
        if (fileName != null && !fileName.isBlank() && isArchive(fileName)) {
            return buildFilePath(root, folder, fileName);
        }

        // Перевіряємо комбінацію: folder + fileName
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
        log.debug("readFromArchive: archivePath={}, entryName={}, fileName={}", archivePath, entryName, fileName);

        Exception lastException = null;

        for (Charset charset : ZIP_CHARSETS) {
            try (ZipFile zip = new ZipFile(archivePath.toFile(), charset)) {
                log.debug("Trying to read ZIP with charset: {}", charset);
                byte[] result = tryReadFromZip(zip, entryName, fileName);
                if (result != null) {
                    log.debug("Successfully read with charset: {}", charset);
                    return result;
                }
            } catch (Exception e) {
                log.debug("Failed to read ZIP with charset {}: {}", charset, e.getMessage());
                lastException = e;
            }
        }

        // Спроба з дефолтним кодуванням
        try (ZipFile zip = new ZipFile(archivePath.toFile())) {
            log.debug("Trying to read ZIP with default charset");
            byte[] result = tryReadFromZip(zip, entryName, fileName);
            if (result != null) {
                log.debug("Successfully read with default charset");
                return result;
            }
        } catch (Exception e) {
            log.debug("Failed to read ZIP with default charset: {}", e.getMessage());
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

        // 1. Шукаємо за точним entryName
        if (entryName != null && !entryName.isBlank()) {
            targetEntry = zip.getEntry(entryName);
            if (targetEntry != null) {
                log.debug("Found entry by exact name: {}", entryName);
                return zip.getInputStream(targetEntry).readAllBytes();
            }
        }

        // 2. Шукаємо за fileName (ігноруючи шлях)
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
                    log.debug("Found entry by fileName: {} -> {}", searchName, entry.getName());
                    return zip.getInputStream(targetEntry).readAllBytes();
                }
            }
        }

        // 3. Шукаємо перший FB2
        if (targetEntry == null) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".fb2") || name.endsWith(".fbd")) {
                    targetEntry = entry;
                    log.debug("Found first FB2 entry: {}", entry.getName());
                    return zip.getInputStream(targetEntry).readAllBytes();
                }
            }
        }

        return null;
    }
}