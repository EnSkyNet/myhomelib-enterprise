package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.parser.Fb2DomParser;
import com.myhomelibcorp.reader.parser.StreamingFb2Reader;
import com.myhomelibcorp.reader.renderer.DocumentToHtmlConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
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

    private static final long STREAMING_THRESHOLD = 1024 * 1024; // 1 MB

    private final Fb2DomParser domParser = new Fb2DomParser();
    private final StreamingFb2Reader streamingReader = new StreamingFb2Reader();
    private final DocumentToHtmlConverter htmlConverter = new DocumentToHtmlConverter();

    public String loadBookContent(BookDto book) throws Exception {
        log.info("Завантаження книги: id={}, title={}", book.getId(), book.getTitle());
        log.debug("fileName={}, folder={}, archiveEntry={}, collectionRoot={}",
                book.getFileName(), book.getFolder(), book.getArchiveEntry(), book.getCollectionRoot());

        Path archivePath = null;
        String entryName = null;
        Path bookPath = null;
        long fileSize = 0;

        String archiveEntry = book.getArchiveEntry();

        if (archiveEntry != null && !archiveEntry.isBlank()) {
            archivePath = getArchivePath(book);
            if (archivePath == null) {
                log.warn("Архів не знайдено для книги: {}", book.getTitle());
                return createFallbackHtml(book, "Архів не знайдено: " + book.getFileName());
            }
            if (!Files.exists(archivePath)) {
                log.warn("Архів не існує: {}", archivePath);
                return createFallbackHtml(book, "Архів не існує: " + archivePath);
            }
            fileSize = Files.size(archivePath);
            entryName = archiveEntry;
            log.info("Архів знайдено: {}, розмір: {} байт", archivePath, fileSize);
        } else {
            bookPath = buildFilePath(book);
            if (bookPath == null) {
                log.warn("Шлях до файлу не вдалося побудувати для книги: {}", book.getTitle());
                return createFallbackHtml(book, "Шлях до файлу не вказано");
            }
            if (!Files.exists(bookPath)) {
                log.warn("Файл не існує: {}", bookPath);
                return createFallbackHtml(book, "Файл не існує: " + bookPath);
            }
            fileSize = Files.size(bookPath);
            log.info("Файл знайдено: {}, розмір: {} байт", bookPath, fileSize);
        }

        // Визначаємо, який парсер використовувати
        if (fileSize > STREAMING_THRESHOLD) {
            log.info("Використання потокового парсера для файлу розміром {} bytes", fileSize);
            return loadWithStreamingParser(book, archivePath, entryName, bookPath);
        } else {
            log.info("Використання DOM парсера для файлу розміром {} bytes", fileSize);
            return loadWithDomParser(book, archivePath, entryName, bookPath);
        }
    }

    private String createFallbackHtml(BookDto book, String error) {
        return """
                <html>
                <body>
                    <h1>Помилка завантаження книги</h1>
                    <p><b>Назва:</b> %s</p>
                    <p><b>Автор:</b> %s</p>
                    <p><b>Помилка:</b> %s</p>
                    <hr/>
                    <p><i>Перевірте, чи файл існує та чи доступний для читання.</i></p>
                </body>
                </html>
                """.formatted(
                book.getTitle() != null ? book.getTitle() : "Без назви",
                book.getAuthorsText() != null ? book.getAuthorsText() : "Невідомий автор",
                error
        );
    }

    private String loadWithDomParser(BookDto book, Path archivePath, String entryName, Path bookPath) throws Exception {
        com.myhomelibcorp.reader.model.BookDocument document;

        if (archivePath != null && entryName != null) {
            try (InputStream is = getEntryStream(archivePath, entryName)) {
                if (is == null) {
                    return createFallbackHtml(book, "Не вдалося прочитати запис з архіву: " + entryName);
                }
                document = domParser.parse(is);
            }
        } else if (bookPath != null) {
            try (InputStream is = Files.newInputStream(bookPath)) {
                document = domParser.parse(is);
            }
        } else {
            return createFallbackHtml(book, "Немає джерела для читання");
        }

        return htmlConverter.convert(document);
    }

    private String loadWithStreamingParser(BookDto book, Path archivePath, String entryName, Path bookPath) throws Exception {
        List<Chapter> chapters = new ArrayList<>();

        if (archivePath != null && entryName != null) {
            try (InputStream is = getEntryStream(archivePath, entryName)) {
                if (is == null) {
                    return createFallbackHtml(book, "Не вдалося прочитати запис з архіву: " + entryName);
                }
                streamingReader.readChapters(is, chapters::add);
            }
        } else if (bookPath != null) {
            try (InputStream is = Files.newInputStream(bookPath)) {
                streamingReader.readChapters(is, chapters::add);
            }
        } else {
            return createFallbackHtml(book, "Немає джерела для читання");
        }

        if (chapters.isEmpty()) {
            log.warn("Потоковий парсер не знайшов параграфів, використовуємо DOM");
            return loadWithDomParser(book, archivePath, entryName, bookPath);
        }

        var metadata = extractMetadata(book);
        var document = com.myhomelibcorp.reader.model.BookDocument.builder()
                .metadata(metadata)
                .chapters(chapters)
                .footnotes(new ArrayList<>())
                .images(new ArrayList<>())
                .build();

        return htmlConverter.convert(document);
    }

    // ==================== МЕТОДИ ДЛЯ РОБОТИ З ФАЙЛАМИ ====================

    private Path getArchivePath(BookDto book) {
        String fileName = book.getFileName();
        String folder = book.getFolder();
        String root = book.getCollectionRoot();
        String archiveEntry = book.getArchiveEntry();

        log.debug("getArchivePath: fileName={}, folder={}, root={}, archiveEntry={}",
                fileName, folder, root, archiveEntry);

        // Спроба 1: folder як архів
        if (folder != null && !folder.isBlank()) {
            Path folderPath = Paths.get(folder);
            if (isArchivePath(folder) && Files.exists(folderPath)) {
                return folderPath;
            }
            // folder + fileName як архів
            if (fileName != null && !fileName.isBlank() && isArchivePath(fileName)) {
                Path fullPath = folderPath.resolve(fileName);
                if (Files.exists(fullPath)) {
                    return fullPath;
                }
            }
        }

        // Спроба 2: корінь + folder + fileName
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
                // folderPath сам по собі може бути архівом (якщо це файл)
                if (Files.exists(folderPath) && isArchivePath(folderPath.toString())) {
                    return folderPath;
                }
            }
            if (fileName != null && !fileName.isBlank()) {
                Path fullPath = rootPath.resolve(fileName);
                if (Files.exists(fullPath) && isArchivePath(fileName)) {
                    return fullPath;
                }
            }
        }

        // Спроба 3: fileName сам по собі
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

    private InputStream getEntryStream(Path archivePath, String entryName) {
        if (!Files.exists(archivePath)) {
            log.warn("Архів не існує: {}", archivePath);
            return null;
        }

        for (Charset charset : ZIP_CHARSETS) {
            try {
                ZipFile zip = new ZipFile(archivePath.toFile(), charset);
                ZipEntry entry = zip.getEntry(entryName);

                if (entry == null) {
                    // Шукаємо за ім'ям файлу без шляху
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
                    InputStream is = zip.getInputStream(entry);
                    // Важливо: zip буде закрито після читання, але ми повертаємо скопійований потік
                    byte[] data = is.readAllBytes();
                    return new java.io.ByteArrayInputStream(data);
                }
            } catch (Exception e) {
                log.debug("Не вдалося прочитати архів з кодуванням {}: {}", charset, e.getMessage());
            }
        }
        return null;
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

    private com.myhomelibcorp.reader.model.BookMetadata extractMetadata(BookDto book) {
        List<String> authors = new ArrayList<>();
        if (book.getAuthorsText() != null && !book.getAuthorsText().isEmpty()) {
            for (String a : book.getAuthorsText().split(", ")) {
                if (!a.trim().isEmpty()) {
                    authors.add(a.trim());
                }
            }
        }
        return com.myhomelibcorp.reader.model.BookMetadata.builder()
                .title(book.getTitle())
                .authors(authors)
                .language(book.getLanguage())
                .annotation(book.getAnnotation())
                .build();
    }
}