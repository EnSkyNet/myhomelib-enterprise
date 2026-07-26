package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.model.Bookmark;
import com.myhomelibcorp.reader.service.BookmarkManager;
import com.myhomelibcorp.reader.service.ReaderLifecycleManager;
import com.myhomelibcorp.reader.service.ReaderSearchManager;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import jakarta.annotation.PreDestroy;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderWorkspaceController {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final NavigationService navigationService;
    private final SessionService sessionService;
    private final BookmarkManager bookmarkManager;
    private final ReaderSearchManager searchManager;
    private final ReaderLifecycleManager lifecycleManager;

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
    private String currentBookId;

    @FXML
    public void initialize() {
        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);
        webView.setCache(true);

        // Прив'язка ширини WebView
        webView.setMaxWidth(Double.MAX_VALUE);
        webView.setMaxHeight(Double.MAX_VALUE);
        webView.setPrefWidth(Double.MAX_VALUE);

        webView.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                webView.prefWidthProperty().bind(newScene.widthProperty().multiply(0.98));
            }
        });

        ReaderSettings settings = ReaderSettings.getInstance();
        webView.setZoom(1.0);
        settings.save();

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                log.info("Сторінка завантажена, налаштовуємо слухач прогресу");
                // Викликаємо тільки setupProgressListener – він сам запускає таймер
                lifecycleManager.setupProgressListener(webEngine);
                // Відновлюємо позицію (метод публічний)
                if (currentBookId != null) {
                    lifecycleManager.restorePosition(currentBookId);
                }
            } else if (newState == Worker.State.FAILED) {
                log.error("Помилка завантаження сторінки");
            }
        });

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
        this.currentBookId = bookId.asString();
        log.info("Відкриття книги: {}", bookId);
        sessionService.saveLastOpenedBookId(bookId.asString());

        bookQueryRepository.findById(bookId).ifPresentOrElse(book -> {
            BookDto bookDto = bookMapper.toDto(book);
            UiExecutor.runOnUiThread(() -> {
                bookTitleLabel.setText(bookDto.getTitle());
                lifecycleManager.openBook(bookDto, webEngine, progressBar, progressLabel);
                bookmarksLabel.setText("⭐ " + bookmarkManager.getBookmarkCount(bookDto.getId()));
            });
        }, () -> {
            log.warn("Book not found: {}", bookId);
        });
    }

    @FXML private void onBack() {
        lifecycleManager.saveState();
        BookDto currentBook = lifecycleManager.getCurrentBook();
        if (currentBook != null) {
            navigationService.navigateToBook(BookId.fromString(currentBook.getId()));
        } else {
            navigationService.navigateToAuthor(null);
        }
    }

    // ==================== Пошук ====================

    @FXML private void onToggleSearch() { showSearchBar(!searchBar.isVisible()); }
    @FXML private void onSearchClose() { showSearchBar(false); searchField.clear(); }
    @FXML private void onSearchNext() { String q = searchField.getText(); if (q != null && !q.trim().isEmpty()) searchManager.searchNext(webEngine, q.trim(), this::updateSearchStatus); }
    @FXML private void onSearchPrev() { String q = searchField.getText(); if (q != null && !q.trim().isEmpty()) searchManager.searchPrev(webEngine, q.trim(), this::updateSearchStatus); }
    private void showSearchBar(boolean show) { searchBar.setVisible(show); searchBar.setManaged(show); if (show) { searchField.requestFocus(); searchField.selectAll(); } else { searchManager.clearSearch(webEngine, this::updateSearchStatus); webView.requestFocus(); } }
    private void updateSearchStatus(int matchCount, int currentMatch) { searchStatus.setText(matchCount > 0 ? currentMatch + "/" + matchCount : "0/0"); }

    // ==================== Масштаб ====================

    @FXML private void onZoomIn() {
        double z = webView.getZoom() + 0.1;
        webView.setZoom(Math.min(2.0, z));
    }
    @FXML private void onZoomOut() {
        double z = webView.getZoom() - 0.1;
        webView.setZoom(Math.max(0.5, z));
    }
    @FXML private void onZoomReset() { webView.setZoom(1.0); }
    @FXML private void onToggleTheme() {
        ReaderSettings settings = ReaderSettings.getInstance();
        switch (settings.getTheme()) {
            case "light": settings.setTheme("sepia"); break;
            case "sepia": settings.setTheme("dark"); break;
            case "dark": settings.setTheme("amoled"); break;
            default: settings.setTheme("light"); break;
        }
        settings.save();
        BookDto currentBook = lifecycleManager.getCurrentBook();
        if (currentBook != null) {
            // Перезавантажуємо книгу з новою темою
            lifecycleManager.openBook(currentBook, webEngine, progressBar, progressLabel);
            // Відновлюємо позицію після завантаження
            if (currentBookId != null) {
                lifecycleManager.restorePosition(currentBookId);
            }
        }
    }

    // ==================== Зміст ====================

    @FXML
    private void onToggleToc() {
        log.info("Перемикання змісту (ще не реалізовано)");
    }

    // ==================== Закладки ====================

    @FXML private void onAddBookmark() {
        BookDto currentBook = lifecycleManager.getCurrentBook();
        if (currentBook == null) return;
        double progress = progressBar.getProgress();
        // Викликаємо без WebEngine (використовується збережений екземпляр)
        String context = lifecycleManager.getTextAtPosition(progress);
        String chapterTitle = lifecycleManager.getCurrentChapterTitle();
        Bookmark bookmark = Bookmark.create(currentBook.getId(), progress, context, chapterTitle);
        bookmarkManager.addBookmark(currentBook.getId(), bookmark);
        bookmarksLabel.setText("⭐ " + bookmarkManager.getBookmarkCount(currentBook.getId()));
        log.info("Додано закладку: {}", bookmark.getTitle());
    }

    @FXML private void onOpenBookmarks() {
        BookDto currentBook = lifecycleManager.getCurrentBook();
        if (currentBook == null) return;
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
            controller.setBookId(currentBook.getId(), this::goToBookmark);
            Stage stage = new Stage();
            stage.setTitle("Закладки — " + currentBook.getTitle());
            stage.setScene(new Scene(root, 500, 400));
            stage.initModality(Modality.NONE);
            stage.initOwner(webView.getScene().getWindow());
            stage.show();
        } catch (Exception e) {
            log.error("Failed to open bookmarks dialog", e);
        }
    }

    private void goToBookmark(Bookmark bookmark) {
        if (bookmark == null) return;
        double position = bookmark.getPosition();
        String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + position + ")";
        webEngine.executeScript(script);
        progressBar.setProgress(position);
        progressLabel.setText((int)(position * 100) + "%");
        BookDto currentBook = lifecycleManager.getCurrentBook();
        if (currentBook != null) {
            lifecycleManager.updateProgress(BookId.fromString(currentBook.getId()), (int)(position * 100));
        }
    }

    // ==================== Публічні методи для зовнішнього використання ====================

    public String getTextAtPosition(double position) {
        return lifecycleManager.getTextAtPosition(position);
    }

    public String getCurrentChapterTitle() {
        return lifecycleManager.getCurrentChapterTitle();
    }

    // ==================== Очищення ресурсів ====================

    @PreDestroy
    public void cleanup() {
        lifecycleManager.saveState();
        if (webEngine != null) {
            Platform.runLater(() -> {
                webEngine.loadContent("");
                webEngine.getLoadWorker().cancel();
            });
        }
        log.info("ReaderWorkspaceController очищено");
    }

    public void saveState() {
        lifecycleManager.saveState();
    }
}