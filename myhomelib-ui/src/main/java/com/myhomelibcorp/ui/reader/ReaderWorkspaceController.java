package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.application.usecase.book.UpdateBookUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.Bookmark;
import com.myhomelibcorp.reader.service.BookmarkManager;
import com.myhomelibcorp.reader.service.ReaderContentLoader;
import com.myhomelibcorp.reader.service.ReaderProgressManager;
import com.myhomelibcorp.reader.service.ReaderSearchManager;
import com.myhomelibcorp.reader.service.ReaderThemeManager;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderWorkspaceController {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final NavigationService navigationService;
    private final SessionService sessionService;
    private final UpdateBookUseCase updateBookUseCase;
    private final BookmarkManager bookmarkManager;
    private final ReaderContentLoader contentLoader;
    private final ReaderSearchManager searchManager;
    private final ReaderProgressManager progressManager;
    private final ReaderThemeManager themeManager;

    @FXML private WebView webView;
    @FXML private Label bookTitleLabel;
    @FXML private Label pageLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label bookmarksLabel;
    @FXML private HBox searchBar;
    @FXML private TextField searchField;
    @FXML private Label searchStatus;

    private WebEngine webEngine;
    private BookDto currentBook;
    private BookDocument currentDocument;
    private int totalPages = 0;
    private int bookmarksCount = 0;

    // ==================== Ініціалізація ====================

    @FXML
    public void initialize() {
        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);
        webView.setCache(true);

        // Завантажуємо налаштування теми та масштабу
        themeManager.loadSettings();
        webView.setZoom(themeManager.getZoomLevel());
        themeManager.applyTheme(webEngine);

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                updatePageInfo();
                double progress = progressBar.getProgress();
                progressManager.restoreScrollPosition(webEngine, progress);
                progressManager.startProgressTimer(webEngine,
                        currentBook != null ? currentBook.getId() : null,
                        progressBar, progressLabel, null);
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

        webView.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.F) {
                event.consume();
                showSearchBar(true);
            } else if (event.getCode() == KeyCode.ESCAPE) {
                if (searchBar.isVisible()) {
                    event.consume();
                    showSearchBar(false);
                }
            } else if (event.isControlDown() && event.getCode() == KeyCode.G) {
                event.consume();
                onSearchNext();
            } else if (event.isShiftDown() && event.isControlDown() && event.getCode() == KeyCode.G) {
                event.consume();
                onSearchPrev();
            }
        });

        searchField.textProperty().addListener((obs, old, query) -> {
            if (query != null && !query.trim().isEmpty()) {
                searchManager.performSearch(webEngine, query.trim(), this::updateSearchStatus);
            } else {
                searchManager.clearSearch(webEngine, this::updateSearchStatus);
            }
        });

        searchBar.visibleProperty().addListener((obs, old, visible) -> {
            if (!visible) {
                searchManager.clearSearch(webEngine, this::updateSearchStatus);
            }
        });

        searchBar.setVisible(false);
        searchBar.setManaged(false);
    }

    public void setBookId(BookId bookId) {
        sessionService.saveLastOpenedBookId(bookId.asString());

        bookQueryRepository.findById(bookId).ifPresentOrElse(book -> {
            currentBook = bookMapper.toDto(book);
            UiExecutor.runOnUiThread(() -> {
                String title = currentBook.getTitle();
                bookTitleLabel.setText(title != null && !title.isEmpty() ? title : "Без назви");
                loadBookContent(currentBook);
                progressBar.setProgress(currentBook.getProgress() / 100.0);
                progressLabel.setText(currentBook.getProgress() + "%");
                bookmarksCount = bookmarkManager.getBookmarkCount(currentBook.getId());
                bookmarksLabel.setText("⭐ " + bookmarksCount);
            });
        }, () -> {
            log.warn("Book not found: {}", bookId);
        });
    }

    private void loadBookContent(BookDto book) {
        try {
            String html = contentLoader.loadBookContent(book);
            webEngine.loadContent(html);
            log.info("Книгу завантажено: {}", book.getTitle());
        } catch (Exception e) {
            log.error("Failed to load book", e);
            webEngine.loadContent("<html><body><h1>Помилка завантаження</h1><pre>" + e.getMessage() + "</pre></body></html>");
        }
    }

    // ==================== Пошук ====================

    @FXML
    private void onToggleSearch() {
        showSearchBar(!searchBar.isVisible());
    }

    @FXML
    private void onSearchClose() {
        showSearchBar(false);
        searchField.clear();
    }

    @FXML
    private void onSearchNext() {
        String query = searchField.getText();
        if (query != null && !query.trim().isEmpty()) {
            searchManager.searchNext(webEngine, query.trim(), this::updateSearchStatus);
        }
    }

    @FXML
    private void onSearchPrev() {
        String query = searchField.getText();
        if (query != null && !query.trim().isEmpty()) {
            searchManager.searchPrev(webEngine, query.trim(), this::updateSearchStatus);
        }
    }

    private void showSearchBar(boolean show) {
        searchBar.setVisible(show);
        searchBar.setManaged(show);
        if (show) {
            searchField.requestFocus();
            searchField.selectAll();
        } else {
            searchManager.clearSearch(webEngine, this::updateSearchStatus);
            webView.requestFocus();
        }
    }

    private void updateSearchStatus(int matchCount, int currentMatch) {
        if (matchCount > 0) {
            searchStatus.setText(currentMatch + "/" + matchCount);
        } else {
            searchStatus.setText("0/0");
        }
    }

    // ==================== Прогрес ====================

    private void saveProgress() {
        if (currentBook != null) {
            progressManager.saveProgress(webEngine, currentBook.getId());
        }
    }

    // ==================== Дії користувача ====================

    @FXML
    private void onBack() {
        saveProgress();
        navigationService.navigateToBook(BookId.fromString(currentBook.getId()));
    }

    @FXML
    private void onZoomIn() {
        double newZoom = Math.min(2.0, themeManager.getZoomLevel() + 0.1);
        themeManager.setZoomLevel(webEngine, newZoom, webView);
    }

    @FXML
    private void onZoomOut() {
        double newZoom = Math.max(0.5, themeManager.getZoomLevel() - 0.1);
        themeManager.setZoomLevel(webEngine, newZoom, webView);
    }

    @FXML
    private void onZoomReset() {
        themeManager.setZoomLevel(webEngine, 1.0, webView);
    }

    @FXML
    private void onToggleTheme() {
        themeManager.toggleTheme(webEngine);
    }

    @FXML
    private void onToggleToc() {
        log.info("Перемикання змісту (ще не реалізовано)");
        if (currentDocument != null && currentDocument.getChapters() != null) {
            log.info("Кількість розділів: {}", currentDocument.getChapters().size());
        }
    }

    // ==================== Закладки ====================

    @FXML
    private void onAddBookmark() {
        if (currentBook == null) return;
        Object progressObj = webEngine.executeScript("window.progress");
        if (progressObj instanceof Number) {
            double position = ((Number) progressObj).doubleValue();
            if (position < 0) position = 0;
            if (position > 1) position = 1;
            String context = getTextAtPosition(position);
            String chapterTitle = getCurrentChapterTitle();
            Bookmark bookmark = Bookmark.create(currentBook.getId(), position, context, chapterTitle);
            bookmarkManager.addBookmark(currentBook.getId(), bookmark);
            bookmarksCount = bookmarkManager.getBookmarkCount(currentBook.getId());
            bookmarksLabel.setText("⭐ " + bookmarksCount);
            log.info("Додано закладку: {}", bookmark.getTitle());
        }
    }

    @FXML
    private void onOpenBookmarks() {
        if (currentBook == null) return;
        final String bookIdForDialog = currentBook.getId();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/bookmark-dialog.fxml"));
            loader.setControllerFactory(controllerClass -> {
                if (controllerClass == BookmarkDialogController.class) {
                    return new BookmarkDialogController(bookmarkManager);
                }
                try {
                    return controllerClass.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Parent root = loader.load();
            BookmarkDialogController controller = loader.getController();
            controller.setBookId(bookIdForDialog, this::goToBookmark);
            Stage stage = new Stage();
            stage.setTitle("Закладки – " + currentBook.getTitle());
            stage.setScene(new Scene(root, 500.0, 400.0));
            stage.initModality(Modality.NONE);
            stage.initOwner(webView.getScene().getWindow());
            stage.show();
        } catch (Exception e) {
            log.error("Failed to open bookmarks dialog", e);
        }
    }

    private void goToBookmark(Bookmark bookmark) {
        if (bookmark == null || currentBook == null) return;
        double position = bookmark.getPosition();
        String script = """
                var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                var target = scrollHeight * %f;
                window.scrollTo(0, target);
                """;
        webEngine.executeScript(String.format(script, position));
        progressBar.setProgress(position);
        progressLabel.setText((int) (position * 100) + "%");
        updateBookUseCase.updateProgress(BookId.fromString(currentBook.getId()), (int) (position * 100));
        currentBook.setProgress((int) (position * 100));
    }

    private String getTextAtPosition(double position) {
        try {
            String js = """
                    var body = document.body.innerText;
                    var len = body.length;
                    var pos = Math.floor(arguments[0] * len);
                    var start = Math.max(0, pos - 100);
                    var end = Math.min(len, pos + 100);
                    body.substring(start, end);
                    """;
            Object result = webEngine.executeScript("(" + js + ")(" + position + ")");
            return result != null ? result.toString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String getCurrentChapterTitle() {
        try {
            Object title = webEngine.executeScript("document.querySelector('.chapter-title')?.innerText || ''");
            return title != null ? title.toString() : "Розділ";
        } catch (Exception e) {
            return "Розділ";
        }
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

    public void saveState() {
        saveProgress();
        themeManager.saveTheme();
        themeManager.saveZoom();
    }
}