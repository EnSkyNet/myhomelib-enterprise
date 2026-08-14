package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.model.ReaderReadingStats;
import com.myhomelibcorp.reader.service.AutoScrollService;
import com.myhomelibcorp.reader.service.ReaderFacade;
import com.myhomelibcorp.reader.service.ReaderScheduler;
import com.myhomelibcorp.reader.service.ReaderStatsService;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.myhomelibcorp.reader.session.ReaderSessionManager;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.service.NavigationService;
import jakarta.annotation.PreDestroy;
import javafx.animation.AnimationTimer;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class ReaderWorkspaceController implements WorkspaceLifecycle {

    private final NavigationService navigationService;
    private final ReaderFacade readerFacade;
    private final ReaderSessionManager sessionManager;
    private final AutoScrollService autoScrollService;
    private final ReaderScheduler scheduler;
    private final ReaderStatsService statsService;
    private final ApplicationContext springContext;

    @FXML private StackPane webViewContainer;
    @FXML private Label bookTitleLabel;
    @FXML private Label progressLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label bookmarksLabel;
    @FXML private Label pageInfoLabel;
    @FXML private HBox searchBar;
    @FXML private TextField searchField;
    @FXML private Label searchStatus;

    private WebView webView;
    private WebEngine webEngine;
    private ReaderSession currentSession;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private boolean isClosing = false;
    private Stage tocStage;

    private String lastSearchQuery = "";
    private int searchMatchCount = 0;
    private int searchCurrentMatch = 0;
    private boolean isFullscreen = false;
    private boolean isAutoScrollActive = false;
    private double autoScrollSpeed = 2.0;

    private final AnimationTimer progressUpdateTimer = new AnimationTimer() {
        private long lastUpdate = 0;
        private static final long UPDATE_INTERVAL = 2_000_000_000L;

        @Override
        public void handle(long now) {
            if (now - lastUpdate < UPDATE_INTERVAL) {
                return;
            }
            lastUpdate = now;

            if (currentSession != null && currentSession.isActive()) {
                ReaderPosition pos = readerFacade.getCurrentPosition();
                if (pos != null) {
                    updateProgressBar(pos.getPercent());
                    updatePageInfo();
                }
            }
        }
    };

    @FXML
    public void initialize() {
        if (isInitialized.getAndSet(true)) {
            return;
        }

        createWebView();

        searchBar.setVisible(false);
        searchBar.setManaged(false);

        webViewContainer.setOnKeyPressed(this::onKeyPressed);
        webViewContainer.setFocusTraversable(true);

        progressUpdateTimer.start();

        log.info("ReaderWorkspaceController initialized");
    }

    private void createWebView() {
        if (webView != null) {
            webViewContainer.getChildren().remove(webView);
            webView = null;
            webEngine = null;
        }

        webView = new WebView();
        webView.setCache(false);
        webView.setVisible(true);
        webView.setZoom(1.0);
        webView.setStyle("-fx-padding: 0;");
        webView.prefWidthProperty().bind(webViewContainer.widthProperty());
        webView.prefHeightProperty().bind(webViewContainer.heightProperty());

        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);

        webViewContainer.getChildren().add(webView);
        webViewContainer.setVisible(true);

        log.info("WebView created");
    }

    public void setBookId(BookId bookId) {
        if (bookId == null) {
            log.warn("Cannot open null bookId");
            return;
        }

        closeCurrentBook();
        isClosing = false;

        currentSession = readerFacade.openBook(bookId);

        if (currentSession != null) {
            currentSession.setWebView(webView);
            currentSession.setWebEngine(webEngine);
            currentSession.setProgressBar(progressBar);
            currentSession.setProgressLabel(progressLabel);

            bookTitleLabel.setText(currentSession.getBook().getTitle());
            updateBookmarksCount();

            if (currentSession.getZoom() != 1.0 && webView != null) {
                webView.setZoom(currentSession.getZoom());
            }

            statsService.startReadingSession(currentSession);
            readerFacade.startPeriodicSaving(currentSession);

            readerFacade.loadBookContent(currentSession, () -> {
                if (currentSession != null && currentSession.isActive()) {
                    readerFacade.restorePositionAfterLoad(currentSession);
                    loadAutoScrollSettings();
                }
            });

            log.info("Book opened: {}", currentSession.getBook().getTitle());
        }
    }

    private void loadAutoScrollSettings() {
        if (currentSession == null) {
            return;
        }

        ReaderSettings settings = readerFacade.getSettings();
        autoScrollSpeed = settings.getScrollSpeed();

        if (settings.isAutoScroll()) {
            scheduler.runOnFxThread(() -> {
                if (currentSession != null && currentSession.isActive()) {
                    isAutoScrollActive = autoScrollService.toggle(currentSession);
                    if (isAutoScrollActive) {
                        autoScrollService.setSpeed(currentSession, autoScrollSpeed);
                    }
                    log.info("Auto-scroll started from settings: speed={}", autoScrollSpeed);
                }
            });
        }
    }

    private void closeCurrentBook() {
        if (currentSession != null) {
            statsService.endReadingSession(currentSession);
            autoScrollService.stop(currentSession);
            isAutoScrollActive = false;
            readerFacade.stopPeriodicSaving(currentSession);
            readerFacade.saveCurrentPosition();
            readerFacade.closeBook();
            currentSession = null;
        }
    }

    private void updateProgressBar(double percent) {
        if (progressBar != null) {
            progressBar.setProgress(Math.min(1.0, percent / 100.0));
        }
        if (progressLabel != null) {
            progressLabel.setText((int) percent + "%");
        }

        if (currentSession != null) {
            currentSession.setProgressPercent(percent);
            statsService.updateProgress(currentSession);
        }
    }

    private void updatePageInfo() {
        if (pageInfoLabel == null) {
            return;
        }

        String chapter = readerFacade.getCurrentChapterTitle();
        if (chapter != null && !chapter.isEmpty()) {
            pageInfoLabel.setText("Розділ: " + chapter);
        } else {
            pageInfoLabel.setText("Розділ 1");
        }
    }

    private void updateBookmarksCount() {
        int count = readerFacade.getBookmarkCount();
        bookmarksLabel.setText("⭐ " + count);
    }

    // ==================== Виправлення скролу ====================

    /**
     * Виправляє перекриття тексту скролом.
     * Викликається після завантаження HTML та при зміні ширини.
     */
    private void fixScrollbarOverlap() {
        if (currentSession == null || !currentSession.isActive() || webEngine == null) {
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
            webEngine.executeScript(script);
        } catch (Exception e) {
            log.debug("Failed to fix scrollbar overlap: {}", e.getMessage());
        }
    }

    /**
     * Оновлює макет після зміни налаштувань.
     */
    public void updateLayout() {
        if (currentSession == null || !currentSession.isActive() || webEngine == null) {
            return;
        }
        fixScrollbarOverlap();
    }

    // ==================== TOC ====================

    @FXML
    private void onToggleToc() {
        if (tocStage != null && tocStage.isShowing()) {
            tocStage.close();
            tocStage = null;
            return;
        }

        List<Chapter> chapters = readerFacade.getToc();
        if (chapters.isEmpty()) {
            showInfo("Зміст", "У цій книзі немає розділів");
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/toc-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            TOCController controller = loader.getController();
            controller.setChapters(chapters, this::navigateToChapter);

            tocStage = new Stage();
            tocStage.setTitle("Зміст");
            tocStage.setScene(new Scene(root, 350, 450));
            tocStage.initModality(Modality.NONE);
            tocStage.initOwner(webView.getScene().getWindow());
            tocStage.setOnHidden(e -> tocStage = null);
            tocStage.show();

        } catch (Exception e) {
            log.error("Failed to open TOC", e);
        }
    }

    private void navigateToChapter(Chapter chapter) {
        if (chapter == null) return;
        readerFacade.navigateToChapter(chapter);
        if (tocStage != null) {
            tocStage.close();
            tocStage = null;
        }
        updatePageInfo();
    }

    // ==================== Закладки ====================

    @FXML
    private void onAddBookmark() {
        Bookmark bookmark = readerFacade.addBookmark();
        if (bookmark != null) {
            updateBookmarksCount();
            showInfo("Закладка", "Закладку додано");
        } else {
            showWarning("Помилка", "Не вдалося додати закладку");
        }
    }

    @FXML
    private void onOpenBookmarks() {
        List<Bookmark> bookmarks = readerFacade.getBookmarks();
        if (bookmarks.isEmpty()) {
            showInfo("Закладки", "Немає закладок");
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/bookmark-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            BookmarksController controller = loader.getController();
            controller.setBookmarks(bookmarks,
                    bookmark -> {
                        readerFacade.goToBookmark(bookmark);
                    },
                    bookmark -> {
                        readerFacade.removeBookmark(bookmark.getId());
                        updateBookmarksCount();
                    }
            );

            Stage stage = new Stage();
            stage.setTitle("Закладки (" + bookmarks.size() + ")");
            stage.setScene(new Scene(root, 450, 500));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(webView.getScene().getWindow());
            stage.show();

        } catch (Exception e) {
            log.error("Failed to open bookmarks dialog", e);
        }
    }

    // ==================== Пошук ====================

    @FXML
    private void onToggleSearch() {
        boolean visible = !searchBar.isVisible();
        searchBar.setVisible(visible);
        searchBar.setManaged(visible);
        if (visible) {
            searchField.requestFocus();
            searchField.selectAll();
        } else {
            clearSearch();
        }
    }

    @FXML
    private void onSearchClose() {
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        searchField.clear();
        clearSearch();
    }

    @FXML
    private void onSearchFieldAction() {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            clearSearch();
            return;
        }
        lastSearchQuery = query.trim();
        performSearch(lastSearchQuery);
    }

    @FXML
    private void onSearchNext() {
        if (lastSearchQuery.isEmpty()) {
            return;
        }
        if (searchMatchCount == 0) {
            performSearch(lastSearchQuery);
            return;
        }
        searchCurrentMatch = searchCurrentMatch >= searchMatchCount ? 1 : searchCurrentMatch + 1;
        findInBook(lastSearchQuery, false);
        updateSearchStatus();
    }

    @FXML
    private void onSearchPrev() {
        if (lastSearchQuery.isEmpty()) {
            return;
        }
        if (searchMatchCount == 0) {
            performSearch(lastSearchQuery);
            return;
        }
        searchCurrentMatch = searchCurrentMatch <= 1 ? searchMatchCount : searchCurrentMatch - 1;
        findInBook(lastSearchQuery, true);
        updateSearchStatus();
    }

    private void performSearch(String query) {
        if (webEngine == null || query == null || query.trim().isEmpty()) {
            clearSearch();
            return;
        }

        try {
            String escapedQuery = query.replace("'", "\\'").replace("\"", "\\\"");

            clearHighlight();

            String script = """
                (function() {
                    var query = '%s';
                    var body = document.body;
                    var walker = document.createTreeWalker(
                        body,
                        NodeFilter.SHOW_TEXT,
                        {
                            acceptNode: function(node) {
                                var text = node.textContent.toLowerCase();
                                if (text.indexOf(query.toLowerCase()) !== -1) {
                                    return NodeFilter.FILTER_ACCEPT;
                                }
                                return NodeFilter.FILTER_REJECT;
                            }
                        }
                    );
                    
                    var nodes = [];
                    var node;
                    while (node = walker.nextNode()) {
                        nodes.push(node);
                    }
                    
                    nodes.forEach(function(textNode) {
                        var text = textNode.textContent;
                        var lowerText = text.toLowerCase();
                        var queryLower = query.toLowerCase();
                        var pos = lowerText.indexOf(queryLower);
                        
                        if (pos !== -1) {
                            var parent = textNode.parentNode;
                            var fragment = document.createDocumentFragment();
                            
                            var before = document.createTextNode(text.substring(0, pos));
                            fragment.appendChild(before);
                            
                            var highlightSpan = document.createElement('span');
                            highlightSpan.style.backgroundColor = '#ffeb3b';
                            highlightSpan.style.color = '#000000';
                            highlightSpan.style.padding = '0 2px';
                            highlightSpan.style.borderRadius = '2px';
                            var highlight = document.createTextNode(text.substring(pos, pos + query.length));
                            highlightSpan.appendChild(highlight);
                            fragment.appendChild(highlightSpan);
                            
                            var after = document.createTextNode(text.substring(pos + query.length));
                            fragment.appendChild(after);
                            
                            parent.replaceChild(fragment, textNode);
                        }
                    });
                    
                    return nodes.length;
                })();
            """.formatted(escapedQuery);

            Object result = webEngine.executeScript(script);
            searchMatchCount = result instanceof Number ? ((Number) result).intValue() : 0;

            if (searchMatchCount > 0) {
                searchCurrentMatch = 1;
                scrollToMatch(1);
            } else {
                searchCurrentMatch = 0;
            }

            updateSearchStatus();

        } catch (Exception e) {
            log.warn("Пошук не вдався: {}", e.getMessage());
            searchMatchCount = 0;
            searchCurrentMatch = 0;
            updateSearchStatus();
        }
    }

    private void clearHighlight() {
        if (webEngine == null) return;
        try {
            String script = """
                (function() {
                    var highlights = document.querySelectorAll('span[style*="background-color: #ffeb3b"]');
                    highlights.forEach(function(span) {
                        var parent = span.parentNode;
                        var text = span.textContent;
                        var textNode = document.createTextNode(text);
                        parent.replaceChild(textNode, span);
                        parent.normalize();
                    });
                })();
            """;
            webEngine.executeScript(script);
        } catch (Exception e) {
            log.debug("Failed to clear highlight: {}", e.getMessage());
        }
    }

    private void scrollToMatch(int index) {
        if (webEngine == null) return;
        try {
            String script = """
                (function() {
                    var highlights = document.querySelectorAll('span[style*="background-color: #ffeb3b"]');
                    if (highlights.length > INDEX && INDEX >= 0) {
                        highlights[INDEX].scrollIntoView({ block: 'center', behavior: 'smooth' });
                        return true;
                    }
                    return false;
                })();
            """.replace("INDEX", String.valueOf(index - 1));
            webEngine.executeScript(script);
        } catch (Exception e) {
            log.debug("Failed to scroll to match {}: {}", index, e.getMessage());
        }
    }

    private void findInBook(String query, boolean reverse) {
        if (webEngine == null || query == null || query.trim().isEmpty()) {
            return;
        }

        if (searchMatchCount == 0) {
            performSearch(query);
            return;
        }

        if (reverse) {
            searchCurrentMatch = searchCurrentMatch <= 1 ? searchMatchCount : searchCurrentMatch - 1;
        } else {
            searchCurrentMatch = searchCurrentMatch >= searchMatchCount ? 1 : searchCurrentMatch + 1;
        }

        scrollToMatch(searchCurrentMatch);
        updateSearchStatus();
    }

    private void updateSearchStatus() {
        if (searchMatchCount == 0) {
            searchStatus.setText("0/0");
        } else {
            searchStatus.setText(searchCurrentMatch + "/" + searchMatchCount);
        }
    }

    private void clearSearch() {
        lastSearchQuery = "";
        searchMatchCount = 0;
        searchCurrentMatch = 0;
        searchStatus.setText("0/0");
        clearHighlight();
        if (webEngine != null) {
            try {
                webEngine.executeScript("window.getSelection().removeAllRanges();");
            } catch (Exception e) {
                // ignore
            }
        }
    }

    // ==================== Тема ====================

    @FXML
    private void onToggleTheme() {
        readerFacade.toggleTheme();
        updatePageInfo();
    }

    // ==================== Fullscreen ====================

    @FXML
    private void onToggleFullscreen() {
        Stage stage = (Stage) webView.getScene().getWindow();
        if (stage == null) return;

        isFullscreen = !isFullscreen;
        stage.setFullScreen(isFullscreen);
        log.info("Fullscreen: {}", isFullscreen);
    }

    // ==================== Auto-scroll ====================

    @FXML
    private void onToggleAutoScroll() {
        if (currentSession == null || !currentSession.isActive()) {
            showWarning("Увага", "Спочатку відкрийте книгу");
            return;
        }

        isAutoScrollActive = autoScrollService.toggle(currentSession);
        log.info("Auto-scroll toggled: {}", isAutoScrollActive);
    }

    @FXML
    private void onAutoScrollSpeedUp() {
        if (currentSession == null) {
            showWarning("Увага", "Спочатку відкрийте книгу");
            return;
        }
        autoScrollSpeed = Math.min(5.0, autoScrollSpeed + 0.5);
        autoScrollService.setSpeed(currentSession, autoScrollSpeed);
        log.info("Auto-scroll speed: {}", autoScrollSpeed);
    }

    @FXML
    private void onAutoScrollSpeedDown() {
        if (currentSession == null) {
            showWarning("Увага", "Спочатку відкрийте книгу");
            return;
        }
        autoScrollSpeed = Math.max(0.5, autoScrollSpeed - 0.5);
        autoScrollService.setSpeed(currentSession, autoScrollSpeed);
        log.info("Auto-scroll speed: {}", autoScrollSpeed);
    }

    // ==================== Статистика ====================

    @FXML
    private void onShowStats() {
        if (currentSession == null || !currentSession.isActive()) {
            showWarning("Увага", "Спочатку відкрийте книгу");
            return;
        }

        String bookId = currentSession.getBookId();
        ReaderReadingStats stats = statsService.getStats(bookId);

        if (stats == null) {
            showInfo("Статистика", "Немає даних про читання цієї книги");
            return;
        }

        String chapter = readerFacade.getCurrentChapterTitle();
        ReaderPosition pos = readerFacade.getCurrentPosition();
        int percent = pos != null ? (int) pos.getPercent() : (int) (progressBar.getProgress() * 100);

        String statsText = String.format("""
                📊 Статистика читання
                ════════════════════════
                
                📚 Книга: %s
                📈 Прогрес: %d%%
                📖 Розділ: %s
                
                ⏱ Загальний час: %s
                🎯 Сесій: %d
                📅 Останнє читання: %s
                
                ⏳ Залишилось: %s
                ✅ Завершено: %s
                """,
                stats.getBookTitle(),
                percent,
                chapter != null && !chapter.isEmpty() ? chapter : "Розділ 1",
                stats.getFormattedTotalTime(),
                stats.getReadingSessions(),
                stats.getLastReadFormatted(),
                stats.getEstimatedTimeRemaining(),
                stats.getCompletedAt() != null ? "Так ✅" : "Ні"
        );

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Статистика читання");
        alert.setHeaderText(null);
        alert.setContentText(statsText);
        alert.getDialogPane().setPrefWidth(400);
        alert.showAndWait();
    }

    // ==================== Налаштування ====================

    @FXML
    private void onShowSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/reader-settings.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            ReaderSettingsController controller = loader.getController();
            controller.setOnSaveCallback(() -> {
                if (currentSession != null && currentSession.isActive() && webView != null) {
                    double zoom = webView.getZoom();
                    currentSession.setZoom(zoom);
                    readerFacade.applySettings(currentSession);
                    // Оновлюємо макет після зміни налаштувань
                    fixScrollbarOverlap();
                }
            });

            Stage stage = new Stage();
            stage.setTitle("Налаштування Reader");
            stage.setScene(new Scene(root, 500, 450));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(webView.getScene().getWindow());
            stage.show();

        } catch (Exception e) {
            log.error("Failed to open settings", e);
        }
    }

    // ==================== Zoom ====================

    @FXML
    private void onZoomIn() {
        if (webView == null) return;
        double zoom = webView.getZoom();
        webView.setZoom(Math.min(2.0, zoom + 0.1));
        if (currentSession != null) {
            currentSession.setZoom(webView.getZoom());
        }
    }

    @FXML
    private void onZoomOut() {
        if (webView == null) return;
        double zoom = webView.getZoom();
        webView.setZoom(Math.max(0.5, zoom - 0.1));
        if (currentSession != null) {
            currentSession.setZoom(webView.getZoom());
        }
    }

    @FXML
    private void onZoomReset() {
        if (webView == null) return;
        webView.setZoom(1.0);
        if (currentSession != null) {
            currentSession.setZoom(1.0);
        }
    }

    // ==================== Клавіатура ====================

    @FXML
    private void onKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.F11) {
            event.consume();
            onToggleFullscreen();
            return;
        }

        if (event.isControlDown() && event.getCode() == KeyCode.F) {
            event.consume();
            onToggleSearch();
            return;
        }

        if (event.getCode() == KeyCode.ESCAPE) {
            if (searchBar.isVisible()) {
                event.consume();
                onSearchClose();
            } else if (isFullscreen) {
                event.consume();
                onToggleFullscreen();
            }
            return;
        }

        if (event.isControlDown() && event.getCode() == KeyCode.G) {
            event.consume();
            onSearchNext();
            return;
        }

        if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.G) {
            event.consume();
            onSearchPrev();
            return;
        }

        if (event.isControlDown() && event.getCode() == KeyCode.PLUS) {
            event.consume();
            onZoomIn();
            return;
        }

        if (event.isControlDown() && event.getCode() == KeyCode.MINUS) {
            event.consume();
            onZoomOut();
            return;
        }

        if (event.isControlDown() && event.getCode() == KeyCode.DIGIT0) {
            event.consume();
            onZoomReset();
            return;
        }

        if (event.getCode() == KeyCode.SPACE && !searchBar.isVisible()) {
            event.consume();
            onToggleAutoScroll();
            return;
        }
    }

    // ==================== Навігація ====================

    @FXML
    private void onBack() {
        if (isClosing) {
            return;
        }

        isClosing = true;

        try {
            closeCurrentBook();
        } catch (Exception e) {
            log.warn("Error closing book: {}", e.getMessage());
        } finally {
            isClosing = false;
            navigationService.goBack();
        }
    }

    // ==================== Діалоги ====================

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showWarning(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // ==================== Lifecycle ====================

    @Override
    public void dispose() {
        log.info("ReaderWorkspaceController.dispose()");

        progressUpdateTimer.stop();

        if (currentSession != null) {
            statsService.endReadingSession(currentSession);
            autoScrollService.stop(currentSession);
            isAutoScrollActive = false;
        }

        if (currentSession != null && currentSession.isActive()) {
            readerFacade.stopPeriodicSaving(currentSession);
            readerFacade.saveCurrentPosition();
            readerFacade.closeBook();
            currentSession = null;
        }

        if (tocStage != null) {
            tocStage.close();
            tocStage = null;
        }

        if (webView != null) {
            webViewContainer.getChildren().remove(webView);
            webView = null;
            webEngine = null;
        }

        readerFacade.clearCache();
        autoScrollService.clear();
        statsService.clearCache();

        log.info("ReaderWorkspaceController disposed");
    }

    @PreDestroy
    public void cleanup() {
        dispose();
    }
}