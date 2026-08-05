package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.reader.parser.JsoupFb2Parser;
import com.myhomelibcorp.reader.renderer.DocumentToHtmlConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
@Slf4j
public class ReaderContentLoader {

    private static final Charset[] ZIP_CHARSETS = {
            Charset.forName("IBM866"),
            Charset.forName("Windows-1251"),
            Charset.forName("UTF-8"),
            Charset.forName("KOI8-R")
    };

    private static final int MAX_CACHE_SIZE = 5;
    private final ConcurrentMap<String, String> htmlCache = new ConcurrentHashMap<>();
    private final JsoupFb2Parser fb2Parser = new JsoupFb2Parser();
    private final DocumentToHtmlConverter htmlConverter = new DocumentToHtmlConverter();

    public String loadBookContent(BookDto book) throws Exception {
        if (book == null) {
            throw new IllegalArgumentException("Book cannot be null");
        }

        String bookId = book.getId();

        String cached = htmlCache.get(bookId);
        if (cached != null) {
            log.info("📖 Використання кешованого HTML для книги: {}", book.getTitle());
            return cached;
        }

        log.info("Завантаження книги: id={}, title={}", book.getId(), book.getTitle());

        byte[] fileData = readBookData(book);
        if (fileData == null || fileData.length == 0) {
            log.warn("Дані книги порожні");
            return createFallbackHtml(book, "Дані книги порожні");
        }

        // ПЕРЕДАЄМО byte[] безпосередньо в парсер
        String html;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(fileData)) {
            var document = fb2Parser.parse(bais);
            html = htmlConverter.convert(document);
        }

        if (html == null || html.isEmpty()) {
            log.warn("HTML порожній після парсингу");
            return createFallbackHtml(book, "Не вдалося розпарсити книгу");
        }

        if (!html.contains("<p") && !html.contains("<div") && !html.contains("<body")) {
            log.warn("⚠️ HTML не містить ознак контенту!");
        } else {
            log.info("✅ HTML містить ознаки контенту");
        }

        cacheHtml(bookId, html);

        log.info("Книгу завантажено, HTML розмір: {} chars", html.length());
        return html;
    }
    private void cacheHtml(String bookId, String html) {
        if (htmlCache.size() >= MAX_CACHE_SIZE) {
            htmlCache.clear();
        }
        htmlCache.put(bookId, html);
    }

    public void clearCache() {
        htmlCache.clear();
        log.info("Кеш Reader очищено");
    }

    private byte[] readBookData(BookDto book) throws Exception {
        String archiveEntry = book.getArchiveEntry();

        if (archiveEntry != null && !archiveEntry.isBlank()) {
            Path archivePath = getArchivePath(book);
            if (archivePath == null || !Files.exists(archivePath)) {
                log.warn("Архів не знайдено для книги: {}", book.getTitle());
                return null;
            }
            return readEntryFromArchive(archivePath, archiveEntry);
        }

        Path bookPath = buildFilePath(book);
        if (bookPath == null || !Files.exists(bookPath)) {
            log.warn("Файл не знайдено: {}", bookPath);
            return null;
        }

        try (InputStream is = Files.newInputStream(bookPath)) {
            return is.readAllBytes();
        }
    }

    private byte[] readEntryFromArchive(Path archivePath, String entryName) {
        for (Charset charset : ZIP_CHARSETS) {
            try (ZipFile zip = new ZipFile(archivePath.toFile(), charset)) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    String simpleName = Paths.get(entryName).getFileName().toString();
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.getName().endsWith(simpleName) || e.getName().equals(entryName)) {
                            entry = e;
                            break;
                        }
                    }
                }

                if (entry != null) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        return is.readAllBytes();
                    }
                }
            } catch (Exception e) {
                log.debug("Не вдалося прочитати архів з кодуванням {}: {}", charset, e.getMessage());
            }
        }
        return null;
    }

    private Path getArchivePath(BookDto book) {
        String fileName = book.getFileName();
        String folder = book.getFolder();
        String root = book.getCollectionRoot();

        if (folder != null && !folder.isBlank()) {
            Path folderPath = Paths.get(folder);
            if (isArchivePath(folder) && Files.exists(folderPath)) {
                return folderPath;
            }
            if (fileName != null && !fileName.isBlank() && isArchivePath(fileName)) {
                Path fullPath = folderPath.resolve(fileName);
                if (Files.exists(fullPath)) {
                    return fullPath;
                }
            }
        }

        if (root != null && !root.isBlank()) {
            Path rootPath = Paths.get(root);
            if (folder != null && !folder.isBlank()) {
                Path folderPath = rootPath.resolve(folder);
                if (isArchivePath(folder) && Files.exists(folderPath)) {
                    return folderPath;
                }
                if (fileName != null && !fileName.isBlank() && isArchivePath(fileName)) {
                    Path fullPath = folderPath.resolve(fileName);
                    if (Files.exists(fullPath)) {
                        return fullPath;
                    }
                }
            }
            if (fileName != null && !fileName.isBlank()) {
                Path fullPath = rootPath.resolve(fileName);
                if (Files.exists(fullPath) && isArchivePath(fileName)) {
                    return fullPath;
                }
            }
        }

        if (fileName != null && !fileName.isBlank()) {
            Path p = Paths.get(fileName);
            if (isArchivePath(fileName) && Files.exists(p)) {
                return p;
            }
        }

        return null;
    }

    private boolean isArchivePath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".fb2zip") || lower.endsWith(".fbd");
    }

    private Path buildFilePath(BookDto book) {
        String fileName = book.getFileName();
        String folder = book.getFolder();
        String root = book.getCollectionRoot();

        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        Path fileNamePath = Paths.get(fileName);
        if (fileNamePath.isAbsolute()) {
            return fileNamePath;
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

        return fileNamePath;
    }

    private String createFallbackHtml(BookDto book, String error) {
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
}