package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.reader.model.Bookmark;
import com.myhomelibcorp.reader.service.BookmarkManager;
import com.myhomelibcorp.reader.service.ReaderLifecycleManager;
import com.myhomelibcorp.reader.service.ReaderSearchManager;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.myhomelibcorp.reader.session.ReaderSessionManager;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
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
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderWorkspaceController {

    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final NavigationService navigationService;
    private final SessionService sessionService;
    private final BookmarkManager bookmarkManager;
    private final ReaderSearchManager searchManager;
    private final ReaderLifecycleManager lifecycleManager;
    private final ReaderSessionManager sessionManager;
    private final ApplicationContext springContext;

    @FXML private StackPane webViewContainer;
    @FXML private Label bookTitleLabel;
    @FXML private Label pageLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label bookmarksLabel;
    @FXML private HBox searchBar;
    @FXML private TextField searchField;
    @FXML private Label searchStatus;

    private WebView webView;
    private WebEngine webEngine;
    private ReaderSession currentSession;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private boolean isClosing = false;
    private ChangeListener<Worker.State> loadListener;

    @FXML
    public void initialize() {
        if (isInitialized.getAndSet(true)) {
            log.debug("ReaderWorkspaceController вже ініціалізовано");
            return;
        }

        ReaderSettings settings = ReaderSettings.getInstance();
        settings.save();

        createWebView();

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

        if (webEngine != null) {
            webEngine.getLoadWorker().exceptionProperty().addListener((obs, old, ex) -> {
                if (ex != null) {
                    log.error("Помилка завантаження WebView: ", ex);
                }
            });
        }

        searchField.textProperty().addListener((obs, old, query) -> {
            if (query != null && !query.trim().isEmpty() && webEngine != null) {
                searchManager.performSearch(webEngine, query.trim(), this::updateSearchStatus);
            } else if (webEngine != null) {
                searchManager.clearSearch(webEngine, this::updateSearchStatus);
            }
        });

        searchBar.visibleProperty().addListener((obs, old, visible) -> {
            if (!visible && webEngine != null) {
                searchManager.clearSearch(webEngine, this::updateSearchStatus);
            }
        });

        searchBar.setVisible(false);
        searchBar.setManaged(false);

        // ТІЛЬКИ ПЕРЕВІРКА РОЗМІРІВ, БЕЗ ТЕСТОВОГО HTML
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Platform.runLater(() -> {
                log.info("🔍 Перевірка WebView після ініціалізації");
                log.info("  - webViewContainer.getWidth(): {}", webViewContainer.getWidth());
                log.info("  - webViewContainer.getHeight(): {}", webViewContainer.getHeight());
                log.info("  - webView.isVisible(): {}", webView != null && webView.isVisible());
                log.info("  - webViewContainer.isVisible(): {}", webViewContainer.isVisible());
            });
        }).start();
    }

    private void createWebView() {
        disposeWebView();

        webView = new WebView();
        webView.setCache(false);
        webView.setVisible(true);
        webView.setStyle("-fx-background-color: #ffffff;");

        // Прив'язка розмірів
        webView.prefWidthProperty().bind(webViewContainer.widthProperty());
        webView.prefHeightProperty().bind(webViewContainer.heightProperty());

        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);
        webView.setZoom(1.0);

        loadListener = (obs, oldState, newState) -> {
            if (currentSession != null && !currentSession.isActive()) {
                return;
            }

            log.info("WebView state: {} -> {}", oldState, newState);

            if (newState == Worker.State.SUCCEEDED) {
                log.info("✅ Сторінка завантажена успішно");

                // Примусове встановлення стилів
                try {
                    webEngine.executeScript("""
                        document.body.style.color = '#000000';
                        document.body.style.backgroundColor = '#ffffff';
                        document.body.style.display = 'block';
                        document.body.style.visibility = 'visible';
                        document.body.style.opacity = '1';
                    """);
                    log.info("✅ Примусово встановлено стилі для відображення");
                } catch (Exception e) {
                    log.warn("Не вдалося виконати JS для корекції стилів", e);
                }

                if (currentSession != null && !currentSession.isProgressListenerSetup()) {
                    currentSession.setProgressListenerSetup(true);
                    lifecycleManager.setupProgressListener(currentSession);
                }
            } else if (newState == Worker.State.FAILED) {
                log.error("❌ Помилка завантаження сторінки");
                Throwable exception = webEngine.getLoadWorker().getException();
                if (exception != null) {
                    log.error("Причина: ", exception);
                }
            } else if (newState == Worker.State.RUNNING) {
                log.debug("⏳ Завантаження сторінки...");
            }
        };
        webEngine.getLoadWorker().stateProperty().addListener(loadListener);

        webViewContainer.getChildren().add(webView);
        webViewContainer.setVisible(true);

        // Примусове оновлення розмірів
        Platform.runLater(() -> {
            webViewContainer.requestLayout();
            webView.requestLayout();
            log.info("📐 Розміри: контейнер {}x{}, WebView {}x{}",
                    webViewContainer.getWidth(), webViewContainer.getHeight(),
                    webView.getWidth(), webView.getHeight());
        });

        log.info("Створено новий WebView");
    }

    private void disposeWebView() {
        if (webView != null) {
            if (webEngine != null && loadListener != null) {
                try {
                    webEngine.getLoadWorker().stateProperty().removeListener(loadListener);
                } catch (Exception e) {
                    log.warn("Помилка видалення listener: {}", e.getMessage());
                }
                loadListener = null;
            }

            if (webEngine != null) {
                try {
                    webEngine.executeScript("window.myhomelib = null;");
                } catch (Exception e) {
                    // Ігноруємо
                }
                webEngine.loadContent("");
                webEngine.getLoadWorker().cancel();
            }

            webViewContainer.getChildren().remove(webView);
            webView = null;
            webEngine = null;
        }
    }

    public void setBookId(BookId bookId) {
        if (bookId == null) {
            log.warn("Спроба відкрити null bookId");
            return;
        }

        String newBookId = bookId.asString();

        if (currentSession != null && currentSession.getBookId() != null &&
                currentSession.getBookId().equals(newBookId) && webView != null) {
            log.info("Книга вже відкрита: {}", bookId);
            return;
        }

        closeCurrentBook();
        createWebView();

        this.isClosing = false;

        log.info("Відкриття книги: {}", bookId);
        sessionService.saveLastOpenedBookId(bookId.asString());

        loadBookByIdUseCase.execute(bookId).ifPresentOrElse(bookDto -> {
            UiExecutor.runOnUiThread(() -> {
                bookTitleLabel.setText(bookDto.getTitle());
                currentSession = lifecycleManager.openBook(bookDto, webEngine, progressBar, progressLabel);
                if (currentSession != null) {
                    bookmarksLabel.setText("⭐ " + bookmarkManager.getBookmarkCount(bookDto.getId()));
                }
            });
        }, () -> {
            log.warn("Book not found: {}", bookId);
        });
    }

    private void closeCurrentBook() {
        if (currentSession != null && currentSession.isActive()) {
            log.info("Закриття попередньої книги: {}", currentSession.getBookId());
            lifecycleManager.closeBook(currentSession);
        }
        currentSession = null;
    }

    @FXML
    private void onBack() {
        if (isClosing) {
            log.debug("Вже виконується закриття, пропускаємо");
            return;
        }

        log.info("Натиснуто кнопку Назад - закриваємо Reader");
        isClosing = true;

        try {
            closeCurrentBook();
        } catch (Exception e) {
            log.error("Помилка при закритті Reader", e);
        }

        navigationService.goBack();

        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            isClosing = false;
        }).start();
    }

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
        String q = searchField.getText();
        if (q != null && !q.trim().isEmpty() && webEngine != null) {
            searchManager.searchNext(webEngine, q.trim(), this::updateSearchStatus);
        }
    }

    @FXML
    private void onSearchPrev() {
        String q = searchField.getText();
        if (q != null && !q.trim().isEmpty() && webEngine != null) {
            searchManager.searchPrev(webEngine, q.trim(), this::updateSearchStatus);
        }
    }

    private void showSearchBar(boolean show) {
        searchBar.setVisible(show);
        searchBar.setManaged(show);
        if (show) {
            searchField.requestFocus();
            searchField.selectAll();
        } else if (webEngine != null) {
            searchManager.clearSearch(webEngine, this::updateSearchStatus);
            webView.requestFocus();
        }
    }

    private void updateSearchStatus(int matchCount, int currentMatch) {
        searchStatus.setText(matchCount > 0 ? currentMatch + "/" + matchCount : "0/0");
    }

    @FXML
    private void onZoomIn() {
        if (webView != null) {
            double z = webView.getZoom() + 0.1;
            webView.setZoom(Math.min(2.0, z));
        }
    }

    @FXML
    private void onZoomOut() {
        if (webView != null) {
            double z = webView.getZoom() - 0.1;
            webView.setZoom(Math.max(0.5, z));
        }
    }

    @FXML
    private void onZoomReset() {
        if (webView != null) {
            webView.setZoom(1.0);
        }
    }

    @FXML
    private void onToggleTheme() {
        ReaderSettings settings = ReaderSettings.getInstance();
        String currentTheme = settings.getTheme();
        switch (currentTheme) {
            case "light": settings.setTheme("sepia"); break;
            case "sepia": settings.setTheme("dark"); break;
            case "dark": settings.setTheme("amoled"); break;
            default: settings.setTheme("light"); break;
        }
        settings.save();

        if (currentSession != null && currentSession.isActive()) {
            BookDto book = currentSession.getBook();
            if (book != null) {
                createWebView();
                lifecycleManager.openBook(book, webEngine, progressBar, progressLabel);
            }
        }
    }

    @FXML
    private void onToggleToc() {
        log.info("Перемикання змісту (ще не реалізовано)");
    }

    @FXML
    private void onAddBookmark() {
        BookDto currentBook = lifecycleManager.getCurrentBook();
        if (currentBook == null) {
            log.warn("Спроба додати закладку без активної книги");
            return;
        }
        double progress = progressBar.getProgress();
        String context = lifecycleManager.getTextAtPosition(progress);
        String chapterTitle = lifecycleManager.getCurrentChapterTitle();
        Bookmark bookmark = Bookmark.create(currentBook.getId(), progress, context, chapterTitle);
        bookmarkManager.addBookmark(currentBook.getId(), bookmark);
        bookmarksLabel.setText("⭐ " + bookmarkManager.getBookmarkCount(currentBook.getId()));
        log.info("Додано закладку: {}", bookmark.getTitle());
    }

    @FXML
    private void onOpenBookmarks() {
        BookDto currentBook = lifecycleManager.getCurrentBook();
        if (currentBook == null) {
            log.warn("Спроба відкрити закладки без активної книги");
            return;
        }
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
        if (bookmark == null || webEngine == null) {
            log.warn("Спроба перейти до null закладки");
            return;
        }
        double position = bookmark.getPosition();
        String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + position + ")";
        webEngine.executeScript(script);
        progressBar.setProgress(position);
        progressLabel.setText((int) (position * 100) + "%");
        BookDto currentBook = lifecycleManager.getCurrentBook();
        if (currentBook != null) {
            lifecycleManager.saveState(sessionManager.getCurrentSession());
        }
    }

    @FXML
    private void onShowSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/reader-settings.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();
            Stage stage = new Stage();
            stage.setTitle("⚙ Налаштування Reader");
            stage.setScene(new Scene(root, 500, 450));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(webView.getScene().getWindow());
            ReaderSettingsController controller = loader.getController();
            controller.setOnSaveCallback(() -> {
                if (currentSession != null && currentSession.isActive()) {
                    BookDto book = currentSession.getBook();
                    if (book != null) {
                        createWebView();
                        lifecycleManager.openBook(book, webEngine, progressBar, progressLabel);
                    }
                }
            });
            stage.show();
        } catch (Exception e) {
            log.error("Failed to open reader settings", e);
        }
    }

    public String getTextAtPosition(double position) {
        return lifecycleManager.getTextAtPosition(position);
    }

    public String getCurrentChapterTitle() {
        return lifecycleManager.getCurrentChapterTitle();
    }

    public void saveState() {
        lifecycleManager.saveState(sessionManager.getCurrentSession());
    }

    @PreDestroy
    public void cleanup() {
        if (!isInitialized.get()) {
            return;
        }
        isInitialized.set(false);
        log.info("ReaderWorkspaceController.cleanup()");

        closeCurrentBook();
        disposeWebView();

        try {
            searchField.textProperty().unbind();
        } catch (Exception e) {
            // Ігноруємо
        }
        searchField.clear();

        log.info("ReaderWorkspaceController очищено");
    }
}