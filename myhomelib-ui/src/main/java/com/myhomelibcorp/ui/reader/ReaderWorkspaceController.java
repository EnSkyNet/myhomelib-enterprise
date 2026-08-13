package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.service.ReaderFacade;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.myhomelibcorp.reader.session.ReaderSessionManager;
import com.myhomelibcorp.ui.service.NavigationService;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderWorkspaceController {

    private final NavigationService navigationService;
    private final ReaderFacade readerFacade;
    private final ReaderSessionManager sessionManager;
    private final ApplicationContext springContext;

    @FXML private StackPane webViewContainer;
    @FXML private Label bookTitleLabel;
    @FXML private Label progressLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label bookmarksLabel;
    @FXML private HBox searchBar;
    @FXML private TextField searchField;
    @FXML private Label searchStatus;

    private WebView webView;
    private WebEngine webEngine;
    private ReaderSession currentSession;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private boolean isClosing = false;
    private Stage tocStage;

    @FXML
    public void initialize() {
        if (isInitialized.getAndSet(true)) {
            return;
        }

        createWebView();

        searchField.textProperty().addListener((obs, old, query) -> {
            // Пошук буде додано пізніше
        });

        searchBar.setVisible(false);
        searchBar.setManaged(false);

        log.info("ReaderWorkspaceController initialized");
    }

    private void createWebView() {
        if (webView != null) {
            webViewContainer.getChildren().remove(webView);
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

        if (currentSession != null && currentSession.isActive()) {
            readerFacade.saveCurrentPosition();
            readerFacade.closeBook();
        }

        isClosing = false;

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

            Platform.runLater(() -> {
                readerFacade.restorePosition();
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

        readerFacade.saveCurrentPosition();
        readerFacade.closeBook();

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
    }

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

    // ==================== ПОВНА ВЕРСІЯ ЗАКЛАДОК З Dialog + ListView ====================

    @FXML
    private void onOpenBookmarks() {
        List<Bookmark> bookmarks = readerFacade.getBookmarks();
        if (bookmarks.isEmpty()) {
            showInfo("Закладки", "Немає закладок");
            return;
        }

        // Створюємо діалог
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Закладки (" + bookmarks.size() + ")");
        dialog.initOwner(webView.getScene().getWindow());
        dialog.setResizable(true);

        // Створюємо ListView
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
                    // Створюємо текст з інформацією
                    String text = item.getTitle();
                    if (item.getChapterTitle() != null && !item.getChapterTitle().isEmpty()) {
                        text += " (" + item.getChapterTitle() + ")";
                    }
                    setText(text);

                    // Додаємо дату як підказку
                    if (item.getFormattedDate() != null && !item.getFormattedDate().isEmpty()) {
                        setTooltip(new Tooltip("Створено: " + item.getFormattedDate()));
                    }
                }
            }
        });

        // Подвійний клік - перехід до закладки
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Bookmark selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    readerFacade.goToBookmark(selected);
                    dialog.close();
                }
            }
        });

        // Контекстне меню - видалення
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

        // Кнопки
        ButtonType closeButton = new ButtonType("Закрити", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeButton);

        // Скасовуємо закриття при кліку на Close
        dialog.setResultConverter(buttonType -> null);

        // Компонуємо діалог
        VBox content = new VBox(10);
        content.setStyle("-fx-padding: 10;");

        // Статистика
        Label statsLabel = new Label("Всього закладок: " + bookmarks.size());
        statsLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        // Підказка
        Label hintLabel = new Label("Двічі клікніть для переходу, ПКМ для меню");
        hintLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        content.getChildren().addAll(statsLabel, hintLabel, listView);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(450, 500);
        dialog.getDialogPane().setMinSize(350, 400);

        // Оновлюємо лічильник при зміні списку
        listView.itemsProperty().addListener((obs, old, newList) -> {
            statsLabel.setText("Всього закладок: " + (newList != null ? newList.size() : 0));
        });

        dialog.showAndWait();
    }

    // ==================== КІНЕЦЬ ВЕРСІЇ ЗАКЛАДОК ====================

    private void goToBookmark(Bookmark bookmark) {
        if (bookmark == null) return;
        readerFacade.goToBookmark(bookmark);
    }

    private void deleteBookmark(Bookmark bookmark) {
        if (bookmark == null) return;
        readerFacade.removeBookmark(bookmark.getId());
        updateBookmarksCount();
    }

    private void updateBookmarksCount() {
        int count = readerFacade.getBookmarkCount();
        bookmarksLabel.setText("⭐ " + count);
    }

    @FXML
    private void onToggleSearch() {
        boolean visible = !searchBar.isVisible();
        searchBar.setVisible(visible);
        searchBar.setManaged(visible);
        if (visible) {
            searchField.requestFocus();
            searchField.selectAll();
        }
    }

    @FXML
    private void onSearchClose() {
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        searchField.clear();
        searchStatus.setText("0/0");
    }

    @FXML
    private void onToggleTheme() {
        readerFacade.toggleTheme();
    }

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

    @PreDestroy
    public void cleanup() {
        if (!isInitialized.get()) {
            return;
        }
        isInitialized.set(false);

        log.info("ReaderWorkspaceController.cleanup()");

        readerFacade.saveCurrentPosition();
        readerFacade.closeBook();
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

    @FXML
    private void onSearchNext() {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        // TODO: реалізувати пошук наступного збігу
        // Можна використовувати ReaderJsBridge для пошуку
    }

    @FXML
    private void onSearchPrev() {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        // TODO: реалізувати пошук попереднього збігу
    }
}