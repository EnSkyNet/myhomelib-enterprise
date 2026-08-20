package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.model.ReaderReadingStats;
import com.myhomelibcorp.reader.service.AutoScrollService;
import com.myhomelibcorp.reader.service.ImageCacheService;
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
import javafx.scene.layout.VBox;
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

import java.util.List;
import java.util.concurrent.TimeUnit;
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
    private final ImageCacheService imageCache;
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
    @FXML private VBox loadingIndicator;
    @FXML private ProgressIndicator loadingProgress;
    @FXML private Label loadingLabel;
    @FXML private Label loadingDetailLabel;

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

    private enum LoadState {
        IDLE,
        LOADING,
        CONTENT_LOADED,
        READY
    }
    private LoadState loadState = LoadState.IDLE;

    private String currentParagraphId = "";
    private int currentCharOffset = 0;
    private double currentPercent = -1;

    private final AnimationTimer progressUpdateTimer = new AnimationTimer() {
        private long lastUpdate = 0;
        private static final long UPDATE_INTERVAL = 500_000_000L;

        @Override
        public void handle(long now) {
            if (loadState != LoadState.READY) {
                return;
            }

            if (now - lastUpdate < UPDATE_INTERVAL) {
                return;
            }
            lastUpdate = now;

            if (currentSession == null || !currentSession.isActive()) {
                return;
            }

            ReaderPosition pos = readerFacade.getCurrentPosition();
            if (pos == null) {
                return;
            }

            String anchorId = pos.getAnchorId() != null ? pos.getAnchorId() : "";
            boolean anchorChanged = !anchorId.equals(currentParagraphId);
            boolean charOffsetChanged = Math.abs(pos.getCharOffset() - currentCharOffset) > 1;
            boolean percentChanged = Math.abs(pos.getPercent() - currentPercent) > 0.1;

            if (anchorChanged || charOffsetChanged || percentChanged) {
                updateProgressBar(pos.getPercent());
                updatePageInfo();

                currentParagraphId = anchorId;
                currentCharOffset = pos.getCharOffset();
                currentPercent = pos.getPercent();

                readerFacade.schedulePositionSave(currentSession);
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
        loadState = LoadState.IDLE;
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
        webView.setVisible(false);
        webView.setManaged(false);
    }

    // ==================== OPEN BOOK ====================

    public void setBookId(BookId bookId) {
        if (bookId == null) {
            log.warn("Cannot open null bookId");
            return;
        }

        loadState = LoadState.LOADING;
        currentParagraphId = "";
        currentCharOffset = 0;
        currentPercent = -1;

        showLoadingIndicator("Loading book...", "Reading metadata...");

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

            ReaderSettings settings = readerFacade.getSettings();
            if (settings.isPageMode()) {
                scheduler.runOnFxThread(this::enablePageMode);
            }

            updateLoadingDetail("Parsing and converting...");

            // ===== ЗАВАНТАЖУЄМО КНИГУ =====
            readerFacade.loadBookContent(
                    currentSession,
                    () -> {
                        if (currentSession != null && currentSession.isActive()) {
                            hideLoadingIndicator();

                            // ===== ВІДНОВЛЮЄМО ПОЗИЦІЮ ПІСЛЯ ЗАВАНТАЖЕННЯ =====
                            scheduler.schedule(() -> {
                                scheduler.runOnFxThread(() -> {
                                    if (currentSession != null && currentSession.isActive()) {
                                        readerFacade.restorePositionAfterLoad(currentSession);

                                        // Чекаємо завершення restore
                                        scheduler.schedule(() -> {
                                            scheduler.runOnFxThread(() -> {
                                                if (currentSession != null && currentSession.isActive()) {
                                                    ReaderPosition pos = readerFacade.getCurrentPosition();
                                                    if (pos != null) {
                                                        currentParagraphId = pos.getAnchorId() != null ? pos.getAnchorId() : "";
                                                        currentCharOffset = pos.getCharOffset();
                                                        currentPercent = pos.getPercent();
                                                        updateProgressBar(pos.getPercent());
                                                    }

                                                    loadState = LoadState.READY;
                                                    log.info("Reader is READY");
                                                    loadAutoScrollSettings();
                                                }
                                            });
                                        }, 500, TimeUnit.MILLISECONDS);
                                    }
                                });
                            }, 300, TimeUnit.MILLISECONDS);
                        } else {
                            hideLoadingIndicator();
                            loadState = LoadState.IDLE;
                        }
                    },
                    this::updateLoadingDetail
            );
        } else {
            hideLoadingIndicator();
            loadState = LoadState.IDLE;
            showError("Failed to open book", "The file may be corrupted or missing.");
        }
    }

    private void loadAutoScrollSettings() {
        if (currentSession == null) return;

        ReaderSettings settings = readerFacade.getSettings();
        autoScrollSpeed = settings.getScrollSpeed();

        if (settings.isAutoScroll()) {
            scheduler.runOnFxThread(() -> {
                if (currentSession != null && currentSession.isActive()) {
                    isAutoScrollActive = autoScrollService.toggle(currentSession);
                    if (isAutoScrollActive) {
                        autoScrollService.setSpeed(currentSession, autoScrollSpeed);
                    }
                }
            });
        }
    }

    // ==================== CLOSE BOOK ====================

    private void closeCurrentBook() {
        if (currentSession != null) {
            autoScrollService.stop(currentSession);
            isAutoScrollActive = false;
            readerFacade.closeBook();
            currentSession = null;
            loadState = LoadState.IDLE;
            currentParagraphId = "";
            currentCharOffset = 0;
            currentPercent = -1;
        }
    }

    // ==================== UI UPDATE ====================

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
        if (pageInfoLabel == null) return;

        ReaderSettings settings = readerFacade.getSettings();
        if (settings.isPageMode()) {
            if (webEngine != null) {
                try {
                    String script = """
                        (function() {
                            var data = window.__pageMode;
                            if (!data || !data.enabled) return null;
                            return { current: data.currentPage, total: data.totalPages };
                        })();
                    """;
                    Object result = webEngine.executeScript(script);
                    if (result != null) {
                        String str = result.toString();
                        String current = str.replaceAll(".*current=([0-9]+).*", "$1");
                        String total = str.replaceAll(".*total=([0-9]+).*", "$1");
                        if (current.matches("\\d+") && total.matches("\\d+")) {
                            pageInfoLabel.setText("Page " + current + " / " + total);
                            return;
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
            pageInfoLabel.setText("Page mode");
        } else {
            String chapter = readerFacade.getCurrentChapterTitle();
            pageInfoLabel.setText(chapter != null && !chapter.isEmpty() ? "Chapter: " + chapter : "Chapter 1");
        }
    }

    private void updateBookmarksCount() {
        bookmarksLabel.setText("⭐ " + readerFacade.getBookmarkCount());
    }

    // ==================== PAGE MODE ====================

    @FXML private void onTogglePageMode() {
        if (currentSession == null || !currentSession.isActive() || webEngine == null) {
            showWarning("Attention", "Please open a book first");
            return;
        }

        ReaderSettings settings = readerFacade.getSettings();
        settings.setPageMode(!settings.isPageMode());
        readerFacade.saveSettings();

        if (settings.isPageMode()) {
            enablePageMode();
        } else {
            disablePageMode();
        }
    }

    private void enablePageMode() {
        if (webEngine == null) return;
        scheduler.runOnFxThread(() -> {
            try {
                String script = """
                    (function() {
                        var body = document.body;
                        var html = document.documentElement;
                        if (!body || !html) return;
                        var scrollY = window.scrollY || window.pageYOffset || 0;
                        var pageHeight = window.innerHeight - 80;
                        if (pageHeight <= 0) pageHeight = window.innerHeight - 100;
                        var totalHeight = Math.max(html.scrollHeight, body.scrollHeight, document.documentElement.scrollHeight);
                        var totalPages = Math.max(1, Math.ceil(totalHeight / pageHeight));
                        var currentPage = Math.min(totalPages, Math.max(1, Math.floor(scrollY / pageHeight) + 1));
                        window.__pageMode = {
                            enabled: true,
                            pageHeight: pageHeight,
                            totalPages: totalPages,
                            currentPage: currentPage,
                            lastScrollY: scrollY,
                            totalHeight: totalHeight
                        };
                        window.__savedScrollY = scrollY;
                        var indicator = document.getElementById('page-indicator');
                        if (!indicator) {
                            indicator = document.createElement('div');
                            indicator.id = 'page-indicator';
                            indicator.style.cssText = 'position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,0.75);color:#ffffff;padding:6px 16px;border-radius:20px;font-size:13px;z-index:1000;pointer-events:none;user-select:none;opacity:0.8;transition:opacity 0.3s ease';
                            document.body.appendChild(indicator);
                        }
                        indicator.textContent = currentPage + ' / ' + totalPages;
                        indicator.style.display = 'block';
                        indicator.style.opacity = '0.8';
                        setTimeout(function() { indicator.style.opacity = '0.4'; }, 3000);
                        window.scrollTo({ top: (currentPage - 1) * pageHeight, behavior: 'auto' });
                    })();
                """;
                webEngine.executeScript(script);
                updatePageInfo();
                updateProgressFromPage();
            } catch (Exception e) {
                log.warn("Failed to enable page mode: {}", e.getMessage());
            }
        });
    }

    private void disablePageMode() {
        if (webEngine == null) return;
        scheduler.runOnFxThread(() -> {
            try {
                String script = """
                    (function() {
                        var indicator = document.getElementById('page-indicator');
                        if (indicator) indicator.style.display = 'none';
                        var savedY = window.__savedScrollY || 0;
                        if (savedY > 0) window.scrollTo({ top: savedY, behavior: 'auto' });
                        window.__pageMode = { enabled: false };
                        window.__savedScrollY = null;
                    })();
                """;
                webEngine.executeScript(script);
                updatePageInfo();
            } catch (Exception e) {
                log.warn("Failed to disable page mode: {}", e.getMessage());
            }
        });
    }

    private void navigatePage(int direction) {
        if (webEngine == null || currentSession == null) return;
        scheduler.runOnFxThread(() -> {
            try {
                String script = """
                    (function() {
                        var data = window.__pageMode;
                        if (!data || !data.enabled) return;
                        var newPage = data.currentPage + DIRECTION;
                        if (newPage < 1) newPage = 1;
                        if (newPage > data.totalPages) newPage = data.totalPages;
                        if (newPage !== data.currentPage) {
                            data.currentPage = newPage;
                            window.__savedScrollY = (newPage - 1) * data.pageHeight;
                            window.scrollTo({ top: window.__savedScrollY, behavior: 'auto' });
                            var indicator = document.getElementById('page-indicator');
                            if (indicator) {
                                indicator.textContent = newPage + ' / ' + data.totalPages;
                                indicator.style.opacity = '0.8';
                                clearTimeout(window.__pageIndicatorTimeout);
                                window.__pageIndicatorTimeout = setTimeout(function() { indicator.style.opacity = '0.4'; }, 2000);
                            }
                            window.__pageMode = data;
                        }
                    })();
                """.replace("DIRECTION", String.valueOf(direction));
                webEngine.executeScript(script);
                updatePageInfo();
                updateProgressFromPage();
            } catch (Exception e) {
                log.warn("Failed to navigate page: {}", e.getMessage());
            }
        });
    }

    @FXML private void onNextPage() {
        if (currentSession == null || !currentSession.isActive()) {
            showWarning("Attention", "Please open a book first");
            return;
        }
        ReaderSettings settings = readerFacade.getSettings();
        if (!settings.isPageMode()) {
            settings.setPageMode(true);
            readerFacade.saveSettings();
            enablePageMode();
        } else {
            navigatePage(1);
        }
    }

    @FXML private void onPrevPage() {
        if (currentSession == null || !currentSession.isActive()) {
            showWarning("Attention", "Please open a book first");
            return;
        }
        ReaderSettings settings = readerFacade.getSettings();
        if (!settings.isPageMode()) {
            settings.setPageMode(true);
            readerFacade.saveSettings();
            enablePageMode();
        } else {
            navigatePage(-1);
        }
    }

    @FXML private void onGoToPage() {
        if (currentSession == null || !currentSession.isActive() || webEngine == null) {
            showWarning("Attention", "Please open a book first");
            return;
        }
        try {
            String script = "var data = window.__pageMode; return data && data.enabled ? data.totalPages : 0;";
            Object result = webEngine.executeScript(script);
            int totalPages = result instanceof Number ? ((Number) result).intValue() : 0;
            if (totalPages <= 0) {
                showWarning("Attention", "Page mode is not active or page count unknown");
                return;
            }
            TextInputDialog dialog = new TextInputDialog("1");
            dialog.setTitle("Go to page");
            dialog.setHeaderText("Enter page number (1-" + totalPages + ")");
            dialog.setContentText("Page:");
            dialog.showAndWait().ifPresent(input -> {
                try {
                    int page = Integer.parseInt(input.trim());
                    if (page < 1 || page > totalPages) {
                        showWarning("Attention", "Page must be between 1 and " + totalPages);
                        return;
                    }
                    goToPage(page);
                } catch (NumberFormatException e) {
                    showWarning("Attention", "Enter a valid number");
                }
            });
        } catch (Exception e) {
            log.warn("Failed to show go to page dialog", e);
        }
    }

    private void goToPage(int page) {
        if (webEngine == null) return;
        scheduler.runOnFxThread(() -> {
            try {
                String script = """
                    (function() {
                        var data = window.__pageMode;
                        if (!data || !data.enabled) return false;
                        var newPage = Math.max(1, Math.min(PAGE, data.totalPages));
                        if (newPage !== data.currentPage) {
                            data.currentPage = newPage;
                            window.__savedScrollY = (newPage - 1) * data.pageHeight;
                            window.scrollTo({ top: window.__savedScrollY, behavior: 'auto' });
                            var indicator = document.getElementById('page-indicator');
                            if (indicator) {
                                indicator.textContent = newPage + ' / ' + data.totalPages;
                                indicator.style.opacity = '0.8';
                                clearTimeout(window.__pageIndicatorTimeout);
                                window.__pageIndicatorTimeout = setTimeout(function() { indicator.style.opacity = '0.4'; }, 2000);
                            }
                            window.__pageMode = data;
                        }
                        return true;
                    })();
                """.replace("PAGE", String.valueOf(page));
                webEngine.executeScript(script);
                updatePageInfo();
                updateProgressFromPage();
            } catch (Exception e) {
                log.warn("Failed to go to page {}: {}", page, e.getMessage());
            }
        });
    }

    private void updateProgressFromPage() {
        if (webEngine == null || currentSession == null) return;
        try {
            String script = """
                (function() {
                    var data = window.__pageMode;
                    if (!data || !data.enabled) return null;
                    return (data.currentPage / data.totalPages) * 100;
                })();
            """;
            Object result = webEngine.executeScript(script);
            if (result instanceof Number) {
                updateProgressBar(((Number) result).doubleValue());
            }
        } catch (Exception e) {
            // ignore
        }
    }

    // ==================== TOC ====================

    @FXML private void onToggleToc() {
        if (tocStage != null && tocStage.isShowing()) {
            tocStage.close();
            tocStage = null;
            return;
        }
        List<Chapter> chapters = readerFacade.getToc();
        if (chapters.isEmpty()) {
            showInfo("Table of Contents", "No chapters");
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/toc-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();
            TOCController controller = loader.getController();
            controller.setChapters(chapters, this::navigateToChapter);
            tocStage = new Stage();
            tocStage.setTitle("Table of Contents");
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

    // ==================== BOOKMARKS ====================

    @FXML private void onAddBookmark() {
        Bookmark bookmark = readerFacade.addBookmark();
        if (bookmark != null) {
            updateBookmarksCount();
            showInfo("Bookmark", "Bookmark added");
        }
    }

    @FXML private void onOpenBookmarks() {
        List<Bookmark> bookmarks = readerFacade.getBookmarks();
        if (bookmarks.isEmpty()) {
            showInfo("Bookmarks", "No bookmarks");
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/bookmark-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();
            BookmarksController controller = loader.getController();
            controller.setBookmarks(bookmarks,
                    bookmark -> readerFacade.goToBookmark(bookmark),
                    bookmark -> { readerFacade.removeBookmark(bookmark.getId()); updateBookmarksCount(); }
            );
            Stage stage = new Stage();
            stage.setTitle("Bookmarks (" + bookmarks.size() + ")");
            stage.setScene(new Scene(root, 450, 500));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(webView.getScene().getWindow());
            stage.show();
        } catch (Exception e) {
            log.error("Failed to open bookmarks dialog", e);
        }
    }

    // ==================== SEARCH ====================

    @FXML private void onToggleSearch() {
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

    @FXML private void onSearchClose() {
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        searchField.clear();
        clearSearch();
    }

    @FXML private void onSearchFieldAction() {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            clearSearch();
            return;
        }
        lastSearchQuery = query.trim();
        performSearch(lastSearchQuery);
    }

    @FXML private void onSearchNext() {
        if (lastSearchQuery.isEmpty()) return;
        if (searchMatchCount == 0) { performSearch(lastSearchQuery); return; }
        searchCurrentMatch = searchCurrentMatch >= searchMatchCount ? 1 : searchCurrentMatch + 1;
        scrollToMatch(searchCurrentMatch);
        updateSearchStatus();
    }

    @FXML private void onSearchPrev() {
        if (lastSearchQuery.isEmpty()) return;
        if (searchMatchCount == 0) { performSearch(lastSearchQuery); return; }
        searchCurrentMatch = searchCurrentMatch <= 1 ? searchMatchCount : searchCurrentMatch - 1;
        scrollToMatch(searchCurrentMatch);
        updateSearchStatus();
    }

    private void performSearch(String query) {
        if (webEngine == null || query == null || query.trim().isEmpty()) {
            clearSearch();
            return;
        }
        try {
            clearHighlight();
            String escapedQuery = query.replace("'", "\\'").replace("\"", "\\\"");
            String script = """
                (function() {
                    var query = '%s';
                    var body = document.body;
                    if (!body) return 0;
                    var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, {
                        acceptNode: function(node) {
                            var text = node.textContent.toLowerCase();
                            return text.indexOf(query.toLowerCase()) !== -1 ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
                        }
                    });
                    var nodes = [], node;
                    while (node = walker.nextNode()) nodes.push(node);
                    var highlights = [];
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
                            highlightSpan.className = 'search-highlight';
                            highlightSpan.style.backgroundColor = '#ffeb3b';
                            highlightSpan.style.color = '#000000';
                            highlightSpan.style.padding = '0 2px';
                            highlightSpan.style.borderRadius = '2px';
                            highlightSpan.dataset.matchIndex = highlights.length;
                            var highlight = document.createTextNode(text.substring(pos, pos + query.length));
                            highlightSpan.appendChild(highlight);
                            fragment.appendChild(highlightSpan);
                            highlights.push(highlightSpan);
                            var after = document.createTextNode(text.substring(pos + query.length));
                            fragment.appendChild(after);
                            parent.replaceChild(fragment, textNode);
                        }
                    });
                    window.__searchHighlights = highlights;
                    return highlights.length;
                })();
            """.formatted(escapedQuery);
            Object result = webEngine.executeScript(script);
            searchMatchCount = result instanceof Number ? ((Number) result).intValue() : 0;
            if (searchMatchCount > 0) {
                searchCurrentMatch = 1;
                scheduler.runOnFxThread(() -> { scrollToMatch(searchCurrentMatch); updateSearchStatus(); });
            } else {
                searchCurrentMatch = 0;
                updateSearchStatus();
                showInfo("Search", "Text not found");
            }
        } catch (Exception e) {
            log.warn("Search failed", e);
            searchMatchCount = 0;
            searchCurrentMatch = 0;
            updateSearchStatus();
        }
    }

    private void clearHighlight() {
        if (webEngine == null) return;
        try {
            webEngine.executeScript("""
                (function() {
                    document.querySelectorAll('.search-highlight').forEach(function(span) {
                        var parent = span.parentNode;
                        var text = span.textContent;
                        var textNode = document.createTextNode(text);
                        parent.replaceChild(textNode, span);
                        parent.normalize();
                    });
                    window.__searchHighlights = null;
                    window.__searchQuery = null;
                })();
            """);
        } catch (Exception e) { /* ignore */ }
    }

    private void scrollToMatch(int index) {
        if (webEngine == null || index < 1) return;
        try {
            String script = """
                (function() {
                    var highlights = window.__searchHighlights || document.querySelectorAll('.search-highlight');
                    var idx = INDEX - 1;
                    if (idx >= 0 && idx < highlights.length) {
                        highlights[idx].scrollIntoView({ block: 'center', behavior: 'smooth' });
                        highlights[idx].style.backgroundColor = '#ff6b6b';
                        setTimeout(function() { highlights[idx].style.backgroundColor = '#ffeb3b'; }, 1000);
                        return true;
                    }
                    return false;
                })();
            """.replace("INDEX", String.valueOf(index));
            webEngine.executeScript(script);
        } catch (Exception e) { /* ignore */ }
    }

    private void updateSearchStatus() {
        searchStatus.setText(searchMatchCount == 0 ? "0/0" : searchCurrentMatch + "/" + searchMatchCount);
    }

    private void clearSearch() {
        lastSearchQuery = "";
        searchMatchCount = 0;
        searchCurrentMatch = 0;
        searchStatus.setText("0/0");
        clearHighlight();
        if (webEngine != null) {
            try { webEngine.executeScript("window.getSelection().removeAllRanges();"); } catch (Exception e) { /* ignore */ }
        }
    }

    // ==================== TEXT WIDTH ====================

    @FXML private void onWidthModeNarrow() { setWidthMode("narrow"); }
    @FXML private void onWidthModeMedium() { setWidthMode("medium"); }
    @FXML private void onWidthModeWide() { setWidthMode("wide"); }
    @FXML private void onWidthModeFull() { setWidthMode("full"); }

    private void setWidthMode(String mode) {
        if (currentSession == null || !currentSession.isActive()) {
            showWarning("Attention", "Please open a book first");
            return;
        }
        ReaderSettings settings = readerFacade.getSettings();
        settings.setWidthMode(mode);
        readerFacade.saveSettings();
        readerFacade.applySettings(currentSession);
    }

    // ==================== THEME ====================

    @FXML private void onToggleTheme() {
        readerFacade.toggleTheme();
        updatePageInfo();
    }

    // ==================== FULLSCREEN ====================

    @FXML private void onToggleFullscreen() {
        Stage stage = (Stage) webView.getScene().getWindow();
        if (stage == null) return;
        isFullscreen = !isFullscreen;
        stage.setFullScreen(isFullscreen);
    }

    // ==================== AUTO-SCROLL ====================

    @FXML private void onToggleAutoScroll() {
        if (currentSession == null || !currentSession.isActive()) {
            showWarning("Attention", "Please open a book first");
            return;
        }
        isAutoScrollActive = autoScrollService.toggle(currentSession);
        ReaderSettings settings = readerFacade.getSettings();
        settings.setAutoScroll(isAutoScrollActive);
        readerFacade.saveSettings();
    }

    @FXML private void onAutoScrollSpeedUp() {
        if (currentSession == null) { showWarning("Attention", "Please open a book first"); return; }
        autoScrollSpeed = Math.min(5.0, autoScrollSpeed + 0.5);
        autoScrollService.setSpeed(currentSession, autoScrollSpeed);
        ReaderSettings settings = readerFacade.getSettings();
        settings.setScrollSpeed((int) Math.round(autoScrollSpeed));
        readerFacade.saveSettings();
    }

    @FXML private void onAutoScrollSpeedDown() {
        if (currentSession == null) { showWarning("Attention", "Please open a book first"); return; }
        autoScrollSpeed = Math.max(0.5, autoScrollSpeed - 0.5);
        autoScrollService.setSpeed(currentSession, autoScrollSpeed);
        ReaderSettings settings = readerFacade.getSettings();
        settings.setScrollSpeed((int) Math.round(autoScrollSpeed));
        readerFacade.saveSettings();
    }

    // ==================== CACHE ====================

    @FXML private void onClearCache() {
        if (currentSession != null && currentSession.isActive()) {
            if (imageCache != null) {
                imageCache.clear();
                showInfo("Cache", "Image cache cleared");
            }
        } else {
            showWarning("Attention", "Please open a book first");
        }
    }

    // ==================== STATISTICS ====================

    @FXML private void onShowStats() {
        if (currentSession == null || !currentSession.isActive()) {
            showWarning("Attention", "Please open a book first");
            return;
        }
        ReaderReadingStats stats = readerFacade.getReadingStats();
        if (stats == null) {
            showInfo("Statistics", "No reading data");
            return;
        }
        String chapter = readerFacade.getCurrentChapterTitle();
        ReaderPosition pos = readerFacade.getCurrentPosition();
        int percent = pos != null ? (int) pos.getPercent() : (int) (progressBar.getProgress() * 100);
        String statsText = String.format("""
            📊 Reading Statistics
            ════════════════════════
            📚 Book: %s
            📈 Progress: %d%%
            📖 Chapter: %s
            ⏱ Total time: %s
            🎯 Sessions: %d
            📅 Last read: %s
            ⏳ Time remaining: %s
            ✅ Completed: %s
            """,
                stats.getBookTitle(), percent,
                chapter != null && !chapter.isEmpty() ? chapter : "Chapter 1",
                stats.getFormattedTotalTime(), stats.getReadingSessions(),
                stats.getLastReadFormatted(), stats.getEstimatedTimeRemaining(),
                stats.getCompletedAt() != null ? "Yes ✅" : "No"
        );
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Reading Statistics");
        alert.setHeaderText(null);
        alert.setContentText(statsText);
        alert.getDialogPane().setPrefWidth(400);
        alert.showAndWait();
    }

    // ==================== SETTINGS ====================

    @FXML private void onShowSettings() {
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
                    fixScrollbarOverlap();
                }
            });
            Stage stage = new Stage();
            stage.setTitle("Reader Settings");
            stage.setScene(new Scene(root, 500, 450));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(webView.getScene().getWindow());
            stage.show();
        } catch (Exception e) {
            log.error("Failed to open settings", e);
        }
    }

    // ==================== ZOOM ====================

    @FXML private void onZoomIn() {
        if (webView == null) return;
        webView.setZoom(Math.min(2.0, webView.getZoom() + 0.1));
        if (currentSession != null) currentSession.setZoom(webView.getZoom());
    }

    @FXML private void onZoomOut() {
        if (webView == null) return;
        webView.setZoom(Math.max(0.5, webView.getZoom() - 0.1));
        if (currentSession != null) currentSession.setZoom(webView.getZoom());
    }

    @FXML private void onZoomReset() {
        if (webView == null) return;
        webView.setZoom(1.0);
        if (currentSession != null) currentSession.setZoom(1.0);
    }

    // ==================== SCROLLBAR FIX ====================

    private void fixScrollbarOverlap() {
        if (currentSession == null || !currentSession.isActive() || webEngine == null) return;
        try {
            webEngine.executeScript("""
                (function() {
                    var hasScroll = document.documentElement.scrollHeight > document.documentElement.clientHeight;
                    if (!hasScroll) { document.body.style.paddingRight = '0px'; return; }
                    var scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
                    if (scrollbarWidth > 0) {
                        var body = document.body;
                        if (!body) return;
                        var computedStyle = window.getComputedStyle(body);
                        var maxWidth = computedStyle.maxWidth;
                        if (maxWidth === '100%' || maxWidth === 'none') {
                            body.style.paddingRight = scrollbarWidth + 'px';
                            body.style.boxSizing = 'border-box';
                        }
                    }
                })();
            """);
        } catch (Exception e) { /* ignore */ }
    }

    // ==================== KEYBOARD ====================

    @FXML private void onKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.F11) { event.consume(); onToggleFullscreen(); return; }
        if (event.getCode() == KeyCode.PAGE_UP) { event.consume(); onPrevPage(); return; }
        if (event.getCode() == KeyCode.PAGE_DOWN) { event.consume(); onNextPage(); return; }
        if (event.isControlDown() && event.getCode() == KeyCode.F) { event.consume(); onToggleSearch(); return; }
        if (event.getCode() == KeyCode.ESCAPE) {
            if (searchBar.isVisible()) { event.consume(); onSearchClose(); }
            else if (isFullscreen) { event.consume(); onToggleFullscreen(); }
            return;
        }
        if (event.isControlDown() && event.getCode() == KeyCode.G) {
            if (!searchBar.isVisible()) { event.consume(); onGoToPage(); return; }
            event.consume(); onSearchNext(); return;
        }
        if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.G) {
            event.consume(); onSearchPrev(); return;
        }
        if (event.isControlDown() && event.getCode() == KeyCode.PLUS) { event.consume(); onZoomIn(); return; }
        if (event.isControlDown() && event.getCode() == KeyCode.MINUS) { event.consume(); onZoomOut(); return; }
        if (event.isControlDown() && event.getCode() == KeyCode.DIGIT0) { event.consume(); onZoomReset(); return; }
        if (event.getCode() == KeyCode.SPACE && !searchBar.isVisible()) { event.consume(); onToggleAutoScroll(); return; }
    }

    // ==================== NAVIGATION ====================

    @FXML private void onBack() {
        if (isClosing) return;
        isClosing = true;
        try { closeCurrentBook(); } catch (Exception e) { log.warn("Error closing book", e); }
        finally { isClosing = false; navigationService.goBack(); }
    }

    // ==================== LOADING INDICATOR ====================

    private void showLoadingIndicator(String message, String detail) {
        scheduler.runOnFxThread(() -> {
            loadingIndicator.setVisible(true);
            loadingIndicator.setManaged(true);
            loadingLabel.setText(message != null ? message : "Loading book...");
            loadingDetailLabel.setText(detail != null ? detail : "");
            if (webView != null) { webView.setVisible(false); webView.setManaged(false); }
        });
    }

    private void hideLoadingIndicator() {
        scheduler.runOnFxThread(() -> {
            loadingIndicator.setVisible(false);
            loadingIndicator.setManaged(false);
            if (webView != null) { webView.setVisible(true); webView.setManaged(true); }
        });
    }

    private void updateLoadingDetail(String detail) {
        scheduler.runOnFxThread(() -> loadingDetailLabel.setText(detail != null ? detail : ""));
    }

    // ==================== DIALOGS ====================

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

    private void showError(String title, String message) {
        scheduler.runOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    // ==================== LIFECYCLE ====================

    @Override
    public void dispose() {
        progressUpdateTimer.stop();
        loadState = LoadState.IDLE;
        if (currentSession != null) { closeCurrentBook(); }
        if (tocStage != null) { tocStage.close(); tocStage = null; }
        if (webViewContainer != null && webView != null) {
            webViewContainer.getChildren().remove(webView);
            webView = null;
            webEngine = null;
        }
        loadingIndicator.setVisible(false);
        loadingIndicator.setManaged(false);
        autoScrollService.clear();
        statsService.clearCache();
    }

    @PreDestroy
    public void cleanup() { dispose(); }
}