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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

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

        // Відстеження завантаження сторінки
        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                updatePageInfo();
                applyTheme();
                // Відновити позицію прокрутки
                restoreScrollPosition();
            }
        });

        // Оновлення прогресу при прокрутці (через JavaScript)
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
        bookQueryRepository.findById(bookId).ifPresentOrElse(book -> {
            currentBook = bookMapper.toDto(book);
            // Зберігаємо в сесії
            sessionService.saveLastOpenedBookId(Long.parseLong(bookId.asString()));
            UiExecutor.runOnUiThread(() -> {
                bookTitleLabel.setText(currentBook.getTitle());
                loadBookContent(currentBook);
                // Відновити прогрес
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

    private String extractBookContent(BookDto book) {
        String fileName = book.getFileName();
        String folder = book.getFolder();
        String root = book.getCollectionRoot();

        Path filePath;
        if (root != null && !root.isBlank()) {
            filePath = Paths.get(root, folder != null ? folder : "", fileName);
        } else if (folder != null && !folder.isBlank()) {
            filePath = Paths.get(folder, fileName);
        } else {
            filePath = Paths.get(fileName);
        }

        if (!Files.exists(filePath)) {
            log.warn("Book file not found: {}", filePath);
            return null;
        }

        try {
            if (fileName.endsWith(".fb2") || fileName.endsWith(".fbd")) {
                return convertFb2ToHtml(filePath);
            } else if (fileName.endsWith(".txt")) {
                return Files.readString(filePath);
            } else if (fileName.endsWith(".epub")) {
                // Спрощена обробка EPUB
                return "<p>EPUB підтримка в розробці</p>";
            } else {
                return Files.readString(filePath);
            }
        } catch (Exception e) {
            log.error("Failed to load book content", e);
            return null;
        }
    }

    private String convertFb2ToHtml(Path file) throws Exception {
        try (InputStream is = Files.newInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String content = reader.lines().collect(Collectors.joining("\n"));
            // Спрощене перетворення
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
        // Відновити позицію прокрутки з прогресу
        double progress = progressBar.getProgress();
        if (progress > 0) {
            String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + progress + ")";
            webEngine.executeScript(script);
        }
    }

    @FXML
    private void onBack() {
        // Зберегти прогрес перед виходом
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
            // Зберегти закладку в сесії
            // sessionService.saveBookmark(currentBook.getId(), position);
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

    // Збереження прогресу при закритті програми
    public void saveState() {
        saveProgress();
    }
}