package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.service.AutoScrollService;
import com.myhomelibcorp.reader.service.ReaderFacade;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.myhomelibcorp.reader.session.ReaderSessionManager;
import com.myhomelibcorp.ui.service.NavigationService;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.concurrent.Worker;
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
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderWorkspaceController {

    private final NavigationService navigationService;
    private final ReaderFacade readerFacade;
    private final ReaderSessionManager sessionManager;
    private final AutoScrollService autoScrollService;
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

    // Стан пошуку
    private String lastSearchQuery = "";
    private int searchMatchCount = 0;
    private int searchCurrentMatch = 0;

    // Fullscreen
    private boolean isFullscreen = false;

    // Auto-scroll
    private boolean isAutoScrollActive = false;
    private double autoScrollSpeed = 2.0;

    private final javafx.animation.AnimationTimer progressUpdateTimer = new javafx.animation.AnimationTimer() {
        private long lastUpdate = 0;
        private static final long UPDATE_INTERVAL = 2_000_000_000L; // 2 секунди

        @Override
        public void handle(long now) {
            if (now - lastUpdate < UPDATE_INTERVAL) {
                return;
            }
            lastUpdate = now;

            if (currentSession != null && currentSession.isActive()) {
                // Оновлюємо позицію та прогрес-бар
                ReaderPosition pos = readerFacade.getCurrentPosition();
                if (pos != null) {
                    updateProgressBar(pos.getPercent());
                    updatePageInfo();
                }
            }
        }
    };


    public void initialize() {
        if (isInitialized.getAndSet(true)) {
            return;
        }

        createWebView();

        searchBar.setVisible(false);
        searchBar.setManaged(false);

        webViewContainer.setOnKeyPressed(this::onKeyPressed);
        webViewContainer.setFocusTraversable(true);

        // Запускаємо таймер оновлення прогресу
        progressUpdateTimer.start();

        log.info("ReaderWorkspaceController initialized");
    }

    private void updateProgressBar(double percent) {
        if (progressBar != null) {
            progressBar.setProgress(Math.min(1.0, percent / 100.0));
        }
        if (progressLabel != null) {
            progressLabel.setText((int) percent + "%");
        }
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

        // Зупиняємо авто-скрол
        if (currentSession != null) {
            autoScrollService.stop(currentSession);
            isAutoScrollActive = false;
        }

        // Закриваємо попередню книгу
        if (currentSession != null && currentSession.isActive()) {
            readerFacade.saveCurrentPosition();
            readerFacade.closeBook();
            currentSession = null;
        }

        isClosing = false;

        // ПЕРЕСТВОРЮЄМО WebView ПРИ КОЖНОМУ ВІДКРИТТІ
        createWebView();

        // Відкриваємо нову книгу
        currentSession = readerFacade.openBook(
                bookId,
                webView,
                webEngine,
                progressBar,
                progressLabel
        );

        if (currentSession != null) {
            bookTitleLabel.setText(currentSession.getBook().getTitle());
            updateBookmarksCount();

            readerFacade.startPeriodicSaving(currentSession);

            String sessionId = currentSession.getSessionId();

            webEngine.getLoadWorker().stateProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends Worker.State> obs,
                                    Worker.State oldState,
                                    Worker.State newState) {
                    if (newState == Worker.State.SUCCEEDED) {
                        webEngine.getLoadWorker().stateProperty().removeListener(this);
                        Platform.runLater(() -> {
                            if (sessionManager.isCurrentSession(sessionId)) {
                                readerFacade.restorePosition(() -> {
                                    log.debug("Position restore completed");
                                });
                            }
                        });
                    }
                }
            });

            log.info("Book opened: {}", currentSession.getBook().getTitle());
        }
    }

    @FXML
    private void onBack() {
        if (isClosing) {
            return;
        }

        isClosing = true;

        if (currentSession != null) {
            autoScrollService.stop(currentSession);
            isAutoScrollActive = false;
        }

        if (currentSession != null && currentSession.isActive()) {
            readerFacade.saveCurrentPosition();
            readerFacade.closeBook();
            currentSession = null;
        }

        navigationService.goBack();

        isClosing = false;
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
            tocStage.setScene(new Scene(root, 320, 400));
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

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Закладки (" + bookmarks.size() + ")");
        dialog.initOwner(webView.getScene().getWindow());
        dialog.setResizable(true);

        ListView<Bookmark> listView = new ListView<>();
        listView.getItems().setAll(bookmarks);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Bookmark item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    String text = item.getTitle();
                    if (item.getChapterTitle() != null && !item.getChapterTitle().isEmpty()) {
                        text += " (" + item.getChapterTitle() + ")";
                    }
                    setText(text);
                    if (item.getFormattedDate() != null && !item.getFormattedDate().isEmpty()) {
                        setTooltip(new Tooltip("Створено: " + item.getFormattedDate()));
                    }
                }
            }
        });

        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Bookmark selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    readerFacade.goToBookmark(selected);
                    dialog.close();
                }
            }
        });

        ContextMenu contextMenu = new ContextMenu();
        MenuItem goToItem = new MenuItem("Перейти до закладки");
        goToItem.setOnAction(e -> {
            Bookmark selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                readerFacade.goToBookmark(selected);
                dialog.close();
            }
        });

        MenuItem deleteItem = new MenuItem("Видалити");
        deleteItem.setStyle("-fx-text-fill: #d32f2f;");
        deleteItem.setOnAction(e -> {
            Bookmark selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Видалення закладки");
                confirm.setHeaderText("Видалити закладку?");
                confirm.setContentText("Закладка: " + selected.getTitle());
                confirm.initOwner(dialog.getOwner());

                if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                    readerFacade.removeBookmark(selected.getId());
                    listView.getItems().remove(selected);
                    updateBookmarksCount();
                    dialog.setTitle("Закладки (" + listView.getItems().size() + ")");
                    if (listView.getItems().isEmpty()) {
                        dialog.close();
                        showInfo("Закладки", "Всі закладки видалено");
                    }
                }
            }
        });

        MenuItem deleteAllItem = new MenuItem("Видалити всі");
        deleteAllItem.setStyle("-fx-text-fill: #d32f2f;");
        deleteAllItem.setOnAction(e -> {
            if (!listView.getItems().isEmpty()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Видалення всіх закладок");
                confirm.setHeaderText("Видалити всі " + listView.getItems().size() + " закладок?");
                confirm.setContentText("Цю дію не можна скасувати.");
                confirm.initOwner(dialog.getOwner());

                if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                    for (Bookmark b : listView.getItems()) {
                        readerFacade.removeBookmark(b.getId());
                    }
                    listView.getItems().clear();
                    updateBookmarksCount();
                    dialog.close();
                    showInfo("Закладки", "Всі закладки видалено");
                }
            }
        });

        contextMenu.getItems().addAll(goToItem, new SeparatorMenuItem(), deleteItem, deleteAllItem);
        listView.setContextMenu(contextMenu);

        ButtonType closeButton = new ButtonType("Закрити", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeButton);
        dialog.setResultConverter(buttonType -> null);

        VBox content = new VBox(10);
        content.setStyle("-fx-padding: 10;");

        Label statsLabel = new Label("Всього закладок: " + bookmarks.size());
        statsLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label hintLabel = new Label("Двічі клікніть для переходу, ПКМ для меню");
        hintLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        content.getChildren().addAll(statsLabel, hintLabel, listView);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(450, 500);
        dialog.getDialogPane().setMinSize(350, 400);

        listView.itemsProperty().addListener((obs, old, newList) -> {
            statsLabel.setText("Всього закладок: " + (newList != null ? newList.size() : 0));
        });

        dialog.showAndWait();
    }

    private void updateBookmarksCount() {
        int count = readerFacade.getBookmarkCount();
        bookmarksLabel.setText("⭐ " + count);
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
            String script = """
                (function() {
                    var query = '%s';
                    var found = window.find(query, false, false, true, false, false, false);
                    if (!found) {
                        window.find(query, false, false, true, false, false, false);
                    }
                    var count = 0;
                    var text = document.body.innerText || '';
                    var searchText = query.toLowerCase();
                    var textLower = text.toLowerCase();
                    var pos = textLower.indexOf(searchText);
                    while (pos !== -1) {
                        count++;
                        pos = textLower.indexOf(searchText, pos + searchText.length);
                    }
                    return count;
                })();
            """.formatted(escapedQuery);

            Object result = webEngine.executeScript(script);
            searchMatchCount = result instanceof Number ? ((Number) result).intValue() : 0;
            searchCurrentMatch = searchMatchCount > 0 ? 1 : 0;
            updateSearchStatus();

        } catch (Exception e) {
            log.warn("Пошук не вдався: {}", e.getMessage());
            searchMatchCount = 0;
            searchCurrentMatch = 0;
            updateSearchStatus();
        }
    }

    private void findInBook(String query, boolean reverse) {
        if (webEngine == null || query == null || query.trim().isEmpty()) {
            return;
        }

        try {
            String escapedQuery = query.replace("'", "\\'").replace("\"", "\\\"");
            String script = """
                (function() {
                    var query = '%s';
                    return window.find(query, false, %s, true, false, false, false);
                })();
            """.formatted(escapedQuery, reverse ? "true" : "false");

            webEngine.executeScript(script);

        } catch (Exception e) {
            log.warn("Навігація по пошуку не вдалася: {}", e.getMessage());
        }
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

        String chapter = readerFacade.getCurrentChapterTitle();
        ReaderPosition pos = readerFacade.getCurrentPosition();
        int percent = pos != null ? (int) pos.getPercent() : (int) (progressBar.getProgress() * 100);

        String stats = String.format(
                "📊 Статистика читання\n\n" +
                        "📖 Книга: %s\n" +
                        "📈 Прогрес: %d%%\n" +
                        "📄 Поточний розділ: %s\n" +
                        "⏱ Останнє читання: %s",
                currentSession.getBook().getTitle(),
                percent,
                chapter != null && !chapter.isEmpty() ? chapter : "Розділ 1",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        );

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Статистика читання");
        alert.setHeaderText(null);
        alert.setContentText(stats);
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
                if (currentSession != null && currentSession.isActive()) {
                    webView.setZoom(readerFacade.getZoom());
                    readerFacade.applySettings(currentSession);
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
        double zoom = readerFacade.getZoom();
        readerFacade.setZoom(zoom + 0.1);
    }

    @FXML
    private void onZoomOut() {
        double zoom = readerFacade.getZoom();
        readerFacade.setZoom(zoom - 0.1);
    }

    @FXML
    private void onZoomReset() {
        readerFacade.setZoom(1.0);
    }

    // ==================== Інформація про розділ ====================

    private void updatePageInfo() {
        if (pageInfoLabel == null) {
            log.warn("pageInfoLabel is null, cannot update page info");
            return;
        }

        String chapter = readerFacade.getCurrentChapterTitle();
        if (chapter != null && !chapter.isEmpty()) {
            pageInfoLabel.setText("Розділ: " + chapter);
        } else {
            pageInfoLabel.setText("Розділ 1");
        }
    }

    // ==================== Клавіатурні скорочення ====================

    @FXML
    private void onKeyPressed(KeyEvent event) {
        // F11 - Fullscreen
        if (event.getCode() == KeyCode.F11) {
            event.consume();
            onToggleFullscreen();
            return;
        }

        // Ctrl+F - Пошук
        if (event.isControlDown() && event.getCode() == KeyCode.F) {
            event.consume();
            onToggleSearch();
            return;
        }

        // Escape - закрити пошук або вийти з fullscreen
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

        // Ctrl+G - наступний збіг
        if (event.isControlDown() && event.getCode() == KeyCode.G) {
            event.consume();
            onSearchNext();
            return;
        }

        // Ctrl+Shift+G - попередній збіг
        if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.G) {
            event.consume();
            onSearchPrev();
            return;
        }

        // Ctrl++ - збільшити масштаб
        if (event.isControlDown() && event.getCode() == KeyCode.PLUS) {
            event.consume();
            onZoomIn();
            return;
        }

        // Ctrl+- - зменшити масштаб
        if (event.isControlDown() && event.getCode() == KeyCode.MINUS) {
            event.consume();
            onZoomOut();
            return;
        }

        // Ctrl+0 - скинути масштаб
        if (event.isControlDown() && event.getCode() == KeyCode.DIGIT0) {
            event.consume();
            onZoomReset();
            return;
        }

        // Пробіл - пауза/відновлення авто-скролу
        if (event.getCode() == KeyCode.SPACE && !searchBar.isVisible()) {
            event.consume();
            onToggleAutoScroll();
            return;
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

    @PreDestroy
    public void cleanup() {
        if (!isInitialized.get()) {
            return;
        }
        isInitialized.set(false);

        // Зупиняємо таймер
        progressUpdateTimer.stop();

        log.info("ReaderWorkspaceController.cleanup()");

        if (currentSession != null) {
            autoScrollService.stop(currentSession);
            isAutoScrollActive = false;
        }

        if (currentSession != null && currentSession.isActive()) {
            readerFacade.stopPeriodicSaving(currentSession);
            readerFacade.saveCurrentPosition();
            readerFacade.closeBook();
            currentSession = null;
        }
        readerFacade.clearCache();

        if (tocStage != null) {
            tocStage.close();
            tocStage = null;
        }

        if (webView != null) {
            webViewContainer.getChildren().remove(webView);
            webView = null;
            webEngine = null;
        }

        log.info("ReaderWorkspaceController cleaned up");
    }
}