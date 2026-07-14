package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.application.usecase.book.UpdateBookUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderWorkspaceController {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final NavigationService navigationService;
    private final SessionService sessionService;
    private final UpdateBookUseCase updateBookUseCase;

    @FXML private WebView webView;
    @FXML private Label bookTitleLabel;
    @FXML private Label pageLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label bookmarksLabel;

    private WebEngine webEngine;
    private BookDto currentBook;
    private double zoomLevel = 1.0;
    private boolean darkTheme = false;
    private int currentPage = 0;
    private int totalPages = 0;
    private int bookmarksCount = 0;

    private static final String CSS_LIGHT = "body { background-color: #ffffff; color: #000000; }";
    private static final String CSS_DARK = "body { background-color: #1e1e1e; color: #e0e0e0; }";

    @FXML
    public void initialize() {
        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                updatePageInfo();
                applyTheme();
                restoreScrollPosition();
            }
        });

        webEngine.executeScript(
                "window.addEventListener('scroll', function() {" +
                        "  var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;" +
                        "  var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;" +
                        "  var progress = scrollHeight > 0 ? scrollTop / scrollHeight : 0;" +
                        "  window.progress = progress;" +
                        "});"
        );
    }

    public void setBookId(BookId bookId) {
        sessionService.saveLastOpenedBookId(bookId.asString());

        bookQueryRepository.findById(bookId).ifPresentOrElse(book -> {
            currentBook = bookMapper.toDto(book);
            UiExecutor.runOnUiThread(() -> {
                bookTitleLabel.setText(currentBook.getTitle());
                loadBookContent(currentBook);
                progressBar.setProgress(currentBook.getProgress() / 100.0);
                progressLabel.setText(currentBook.getProgress() + "%");
            });
        }, () -> {
            log.warn("Book not found: {}", bookId);
        });
    }

    private void loadBookContent(BookDto book) {
        String content = extractBookContent(book);
        if (content != null && !content.isEmpty()) {
            String html = buildHtml(content);
            webEngine.loadContent(html);
        } else {
            webEngine.loadContent("<html><body><h1>Не вдалося завантажити книгу</h1><p>Файл не знайдено або пошкоджено.</p></body></html>");
        }
    }

    /**
     * Правильно будує шлях до файлу/архіву на основі полів BookDto.
     */
    private Path buildFilePath(String root, String folder, String fileName) {
        // Якщо fileName абсолютний – використовуємо його
        if (fileName != null && !fileName.isBlank()) {
            Path fileNamePath = Paths.get(fileName);
            if (fileNamePath.isAbsolute()) {
                return fileNamePath;
            }
        }

        // Якщо folder абсолютний – використовуємо folder + fileName
        if (folder != null && !folder.isBlank()) {
            Path folderPath = Paths.get(folder);
            if (folderPath.isAbsolute()) {
                if (fileName != null && !fileName.isBlank()) {
                    return folderPath.resolve(fileName);
                }
                return folderPath;
            }
        }

        // Якщо є root і folder – об'єднуємо root + folder + fileName
        if (root != null && !root.isBlank() && folder != null && !folder.isBlank()) {
            Path rootPath = Paths.get(root);
            Path folderPath = Paths.get(folder);
            if (fileName != null && !fileName.isBlank()) {
                return rootPath.resolve(folderPath).resolve(fileName);
            }
            return rootPath.resolve(folderPath);
        }

        // Якщо є root і fileName – root + fileName
        if (root != null && !root.isBlank() && fileName != null && !fileName.isBlank()) {
            return Paths.get(root).resolve(fileName);
        }

        // Якщо нічого – просто fileName або folder
        if (fileName != null && !fileName.isBlank()) {
            return Paths.get(fileName);
        }
        if (folder != null && !folder.isBlank()) {
            return Paths.get(folder);
        }
        return Paths.get(".");
    }

    private String extractBookContent(BookDto book) {
        String fileName = book.getFileName();
        String folder = book.getFolder();
        String root = book.getCollectionRoot();
        String archiveEntry = book.getArchiveEntry();

        // Якщо є archiveEntry – книга в архіві
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            // Шлях до архіву: або folder, або fileName (якщо folder пустий)
            String archivePathStr = (folder != null && !folder.isBlank()) ? folder : fileName;
            if (archivePathStr == null || archivePathStr.isBlank()) {
                log.warn("Archive path is empty");
                return "<p>Шлях до архіву порожній</p>";
            }
            // Будуємо шлях до архіву
            Path archivePath = buildFilePath(root, null, archivePathStr);
            if (!Files.exists(archivePath)) {
                log.warn("Archive not found: {}", archivePath);
                return "<p>Архів не знайдено: " + archivePath.toString() + "</p>";
            }
            return extractFromArchive(archivePath, archiveEntry);
        }

        // Звичайний файл (не в архіві)
        String filePathStr = (fileName != null && !fileName.isBlank()) ? fileName : folder;
        if (filePathStr == null || filePathStr.isBlank()) {
            log.warn("File path is empty");
            return "<p>Шлях до файлу порожній</p>";
        }
        Path filePath = buildFilePath(root, folder, fileName);
        if (!Files.exists(filePath)) {
            log.warn("Book file not found: {}", filePath);
            return "<p>Файл не знайдено: " + filePath.toString() + "</p>";
        }

        try {
            if (fileName != null && (fileName.endsWith(".fb2") || fileName.endsWith(".fbd"))) {
                return convertFb2ToHtml(filePath);
            } else if (fileName != null && fileName.endsWith(".txt")) {
                return Files.readString(filePath);
            } else if (fileName != null && fileName.endsWith(".epub")) {
                return "<p>EPUB підтримка в розробці</p>";
            } else {
                return Files.readString(filePath);
            }
        } catch (Exception e) {
            log.error("Failed to load book content", e);
            return "<p>Помилка завантаження: " + e.getMessage() + "</p>";
        }
    }

    private String extractFromArchive(Path archivePath, String entryName) {
        // Спроба різних кодувань
        String[] charsets = {"IBM866", "Windows-1251", "UTF-8", "KOI8-R"};
        for (String charsetName : charsets) {
            try {
                java.nio.charset.Charset charset = java.nio.charset.Charset.forName(charsetName);
                try (ZipFile zip = new ZipFile(archivePath.toFile(), charset)) {
                    ZipEntry entry = zip.getEntry(entryName);
                    if (entry == null) {
                        // спробувати за назвою без шляху
                        String simpleName = Paths.get(entryName).getFileName().toString();
                        entry = zip.getEntry(simpleName);
                        if (entry == null) {
                            // пошук за частиною імені
                            Enumeration<? extends ZipEntry> entries = zip.entries();
                            while (entries.hasMoreElements()) {
                                ZipEntry e = entries.nextElement();
                                if (e.getName().endsWith(simpleName)) {
                                    entry = e;
                                    break;
                                }
                            }
                        }
                    }
                    if (entry == null) {
                        continue;
                    }
                    try (InputStream is = zip.getInputStream(entry)) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        if (entryName.endsWith(".fb2") || entryName.endsWith(".fbd")) {
                            // Спрощене перетворення
                            String text = content.replaceAll("<[^>]+>", " ")
                                    .replaceAll("\\s+", " ")
                                    .trim();
                            return "<p>" + text.replace("\n", "</p><p>") + "</p>";
                        }
                        return content;
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to read archive with charset {}: {}", charsetName, e.getMessage());
            }
        }
        log.error("Failed to read archive with all charsets");
        return "<p>Помилка читання архіву: не вдалося розпізнати кодування</p>";
    }

    private String convertFb2ToHtml(Path file) throws Exception {
        try (InputStream is = Files.newInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String content = reader.lines().collect(Collectors.joining("\n"));
            String text = content.replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            return "<p>" + text.replace("\n", "</p><p>") + "</p>";
        }
    }

    private String buildHtml(String content) {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\"/>\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n" +
                "    <style>\n" +
                "        body { font-family: 'Georgia', serif; font-size: 18px; line-height: 1.6; padding: 30px; max-width: 800px; margin: 0 auto; }\n" +
                "        h1, h2, h3 { font-family: 'Arial', sans-serif; }\n" +
                "        p { margin: 0.8em 0; text-align: justify; }\n" +
                "        .book-title { text-align: center; font-size: 24px; margin-bottom: 20px; }\n" +
                "        .author { text-align: center; font-style: italic; margin-bottom: 30px; }\n" +
                "    </style>\n" +
                "    <style id=\"theme-style\">\n" +
                (darkTheme ? CSS_DARK : CSS_LIGHT) +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"book-title\">" + currentBook.getTitle() + "</div>\n" +
                "    <div class=\"author\">" + currentBook.getAuthorsText() + "</div>\n" +
                "    <hr/>\n" +
                content +
                "</body>\n" +
                "</html>";
    }

    private void updatePageInfo() {
        Platform.runLater(() -> {
            try {
                Document doc = webEngine.getDocument();
                if (doc != null) {
                    NodeList paragraphs = doc.getElementsByTagName("p");
                    totalPages = (int) Math.ceil(paragraphs.getLength() / 10.0);
                    pageLabel.setText("1 / " + Math.max(1, totalPages));
                }
            } catch (Exception e) {
                log.debug("Could not update page info", e);
            }
        });
    }

    private void restoreScrollPosition() {
        double progress = progressBar.getProgress();
        if (progress > 0) {
            String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + progress + ")";
            webEngine.executeScript(script);
        }
    }

    @FXML
    private void onBack() {
        saveProgress();
        navigationService.navigateToBook(BookId.fromString(currentBook.getId()));
    }

    private void saveProgress() {
        if (currentBook != null) {
            Object scrollY = webEngine.executeScript("window.progress");
            if (scrollY instanceof Double) {
                int progress = (int) (((Double) scrollY) * 100);
                if (progress > 0 && progress <= 100) {
                    updateBookUseCase.updateProgress(BookId.fromString(currentBook.getId()), progress);
                    currentBook.setProgress(progress);
                }
            }
        }
    }

    @FXML
    private void onZoomIn() {
        zoomLevel = Math.min(2.0, zoomLevel + 0.1);
        webView.setZoom(zoomLevel);
    }

    @FXML
    private void onZoomOut() {
        zoomLevel = Math.max(0.5, zoomLevel - 0.1);
        webView.setZoom(zoomLevel);
    }

    @FXML
    private void onZoomReset() {
        zoomLevel = 1.0;
        webView.setZoom(zoomLevel);
    }

    @FXML
    private void onToggleTheme() {
        darkTheme = !darkTheme;
        applyTheme();
    }

    @FXML
    private void onToggleToc() {
        // Показати зміст
    }

    @FXML
    private void onAddBookmark() {
        String script = "window.scrollY";
        Object scrollY = webEngine.executeScript(script);
        if (scrollY instanceof Number) {
            int position = ((Number) scrollY).intValue();
            bookmarksCount++;
            bookmarksLabel.setText("📖 Закладок: " + bookmarksCount);
        }
    }

    @FXML
    private void onPreviousPage() {
        webEngine.executeScript("window.scrollBy(0, -window.innerHeight * 0.9)");
    }

    @FXML
    private void onNextPage() {
        webEngine.executeScript("window.scrollBy(0, window.innerHeight * 0.9)");
    }

    private void applyTheme() {
        String css = darkTheme ? CSS_DARK : CSS_LIGHT;
        webEngine.executeScript(
                "var style = document.getElementById('theme-style');" +
                        "if (style) { style.innerHTML = '" + css.replace("'", "\\'") + "'; }"
        );
    }

    public void saveState() {
        saveProgress();
    }
}