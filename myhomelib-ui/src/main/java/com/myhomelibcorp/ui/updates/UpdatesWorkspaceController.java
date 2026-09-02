package com.myhomelibcorp.ui.updates;

import com.myhomelibcorp.application.catalog.CatalogUpdateAuthorGroup;
import com.myhomelibcorp.application.catalog.CatalogUpdateItem;
import com.myhomelibcorp.application.catalog.CatalogUpdateService;
import com.myhomelibcorp.application.catalog.CatalogUpdateSnapshot;
import com.myhomelibcorp.application.catalog.CatalogUpdateType;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/** Stage 7: Author -> New/Updated -> Book hierarchy for catalog notifications. */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpdatesWorkspaceController implements WorkspaceLifecycle {
    private final CatalogUpdateService catalogUpdateService;
    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final NavigationService navigationService;
    private final NavigationPanelController navigationPanelController;
    private final UiBackgroundExecutor executor;
    private final LocalizationService localizationService;

    @FXML private TreeView<UpdateTreeNode> updatesTree;
    @FXML private Label summaryLabel;
    @FXML private Label emptyLabel;
    @FXML private Label detailLabel;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button openAuthorButton;
    @FXML private Button openBookButton;
    @FXML private Button downloadButton;
    @FXML private Button acknowledgeButton;
    @FXML private Button acknowledgeAllButton;
    @FXML private Button refreshButton;

    private volatile boolean disposed;
    private long loadGeneration;

    @FXML
    public void initialize() {
        disposed = false;
        updatesTree.setShowRoot(false);
        updatesTree.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(UpdateTreeNode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayText(localizationService));
            }
        });
        updatesTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, selected) -> updateActions(selected));
        updatesTree.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                TreeItem<UpdateTreeNode> selected = updatesTree.getSelectionModel().getSelectedItem();
                if (selected == null) return;
                if (selected.getValue().kind == Kind.AUTHOR) openAuthor();
                else if (selected.getValue().kind == Kind.BOOK) openBook();
                else selected.setExpanded(!selected.isExpanded());
            }
        });
        updateActions(null);
        loadUpdates();
    }

    @FXML
    public void refresh() {
        loadUpdates();
    }

    @FXML
    public void openAuthor() {
        UpdateTreeNode node = selectedNode();
        if (node == null) return;
        if (node.kind == Kind.BOOK) node = node.authorNode();
        if (node == null || node.authorId == null || node.authorId.isBlank()) return;
        navigationService.navigateToAuthor(AuthorId.fromString(node.authorId));
    }

    @FXML
    public void openBook() {
        UpdateTreeNode node = selectedNode();
        if (node == null || node.kind != Kind.BOOK || node.item == null) return;
        navigationService.navigateToBook(BookId.fromString(node.item.bookId()));
    }

    @FXML
    public void acknowledgeSelected() {
        TreeItem<UpdateTreeNode> selected = updatesTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null) return;
        UpdateTreeNode node = selected.getValue();
        setBusy(true);
        detailLabel.setText(localizationService.tr("Позначення оновлення як переглянутого…"));
        executor.submit(() -> {
            if (node.kind == Kind.BOOK && node.item != null) {
                catalogUpdateService.acknowledgeUpdate(BookId.fromString(node.item.bookId()), node.item.type());
            } else if (node.kind == Kind.CATEGORY) {
                for (TreeItem<UpdateTreeNode> child : selected.getChildren()) {
                    UpdateTreeNode childNode = child.getValue();
                    if (childNode != null && childNode.item != null) {
                        catalogUpdateService.acknowledgeUpdate(
                                BookId.fromString(childNode.item.bookId()), childNode.item.type());
                    }
                }
            } else if (node.kind == Kind.AUTHOR && node.authorId != null && !node.authorId.isBlank()) {
                catalogUpdateService.acknowledgeAuthorUpdates(AuthorId.fromString(node.authorId));
            }
            return null;
        }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
            if (disposed) return;
            setBusy(false);
            if (error != null) {
                Throwable cause = UiExceptionSupport.unwrapAsync(error);
                detailLabel.setText(localizationService.tr("Не вдалося позначити оновлення") + ": "
                        + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                return;
            }
            loadUpdates();
        }));
    }

    @FXML
    public void acknowledgeAll() {
        setBusy(true);
        detailLabel.setText(localizationService.tr("Позначення всіх оновлень як переглянутих…"));
        executor.submit(() -> {
                    catalogUpdateService.acknowledgeAllUpdates();
                    return null;
                })
                .whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
                    if (disposed) return;
                    setBusy(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        detailLabel.setText(localizationService.tr("Не вдалося позначити оновлення") + ": "
                                + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                        return;
                    }
                    loadUpdates();
                }));
    }

    @FXML
    public void downloadSelected() {
        UpdateTreeNode node = selectedNode();
        if (node == null || node.kind != Kind.BOOK || node.item == null) return;
        CatalogUpdateItem item = node.item;
        setBusy(true);
        detailLabel.setText(localizationService.tr("Підготовка завантаження…") + " " + item.bookTitle());

        executor.submit(() -> loadBookByIdUseCase.execute(BookId.fromString(item.bookId()))
                        .orElseThrow(() -> new IllegalStateException("Книгу не знайдено: " + item.bookId())))
                .thenCompose(book -> download(item, book))
                .whenComplete((path, error) -> UiExecutor.runOnUiThread(() -> {
                    if (disposed) return;
                    setBusy(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        detailLabel.setText(localizationService.tr("Помилка завантаження") + ": "
                                + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                        return;
                    }
                    detailLabel.setText(localizationService.tr("Завантажено. Оновлення підтверджено."));
                    navigationPanelController.refreshAll();
                    loadUpdates();
                }));
    }

    private CompletableFuture<java.nio.file.Path> download(CatalogUpdateItem item, BookDto book) {
        return item.type() == CatalogUpdateType.UPDATED_DOWNLOADED_BOOK
                ? bookDownloadCoordinator.downloadUpdate(book)
                : bookDownloadCoordinator.ensureLocal(book);
    }

    private void loadUpdates() {
        long generation = ++loadGeneration;
        setBusy(true);
        emptyLabel.setVisible(false);
        emptyLabel.setManaged(false);
        detailLabel.setText(localizationService.tr("Завантаження оновлень…"));
        executor.submit(catalogUpdateService::pendingUpdateSnapshot)
                .whenComplete((snapshot, error) -> UiExecutor.runOnUiThread(() -> {
                    if (disposed || generation != loadGeneration) return;
                    setBusy(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        updatesTree.setRoot(new TreeItem<>(UpdateTreeNode.root()));
                        summaryLabel.setText(localizationService.tr("Оновлення"));
                        detailLabel.setText(localizationService.tr("Не вдалося завантажити оновлення") + ": "
                                + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                        return;
                    }
                    applySnapshot(snapshot == null ? CatalogUpdateSnapshot.empty() : snapshot);
                }));
    }

    private void applySnapshot(CatalogUpdateSnapshot snapshot) {
        summaryLabel.setText(String.format("%s: %,d   •   %s: %,d   •   %s: %,d",
                localizationService.tr("Усього"), snapshot.totalCount(),
                localizationService.tr("Нові книги"), snapshot.newCount(),
                localizationService.tr("Оновлені книги"), snapshot.updatedCount()));

        TreeItem<UpdateTreeNode> root = new TreeItem<>(UpdateTreeNode.root());
        for (CatalogUpdateAuthorGroup author : snapshot.authors()) {
            TreeItem<UpdateTreeNode> authorItem = new TreeItem<>(UpdateTreeNode.author(author));
            authorItem.setExpanded(true);
            if (!author.newBooks().isEmpty()) {
                TreeItem<UpdateTreeNode> category = new TreeItem<>(UpdateTreeNode.category(
                        author, CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR, author.newBooks().size()));
                for (CatalogUpdateItem item : author.newBooks()) {
                    category.getChildren().add(new TreeItem<>(UpdateTreeNode.book(author, item)));
                }
                category.setExpanded(true);
                authorItem.getChildren().add(category);
            }
            if (!author.updatedBooks().isEmpty()) {
                TreeItem<UpdateTreeNode> category = new TreeItem<>(UpdateTreeNode.category(
                        author, CatalogUpdateType.UPDATED_DOWNLOADED_BOOK, author.updatedBooks().size()));
                for (CatalogUpdateItem item : author.updatedBooks()) {
                    category.getChildren().add(new TreeItem<>(UpdateTreeNode.book(author, item)));
                }
                category.setExpanded(true);
                authorItem.getChildren().add(category);
            }
            root.getChildren().add(authorItem);
        }
        updatesTree.setRoot(root);

        boolean empty = snapshot.totalCount() == 0;
        updatesTree.setVisible(!empty);
        updatesTree.setManaged(!empty);
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
        if (empty) {
            emptyLabel.setText(localizationService.tr("Немає нових оновлень каталогу"));
            detailLabel.setText(localizationService.tr("Усі завантажені оновлення вже підтверджено."));
        } else {
            detailLabel.setText(localizationService.tr("Виберіть книгу для перегляду або завантаження."));
        }
        updateActions(null);
    }

    private void updateActions(TreeItem<UpdateTreeNode> selected) {
        UpdateTreeNode node = selected == null ? null : selected.getValue();
        boolean book = node != null && node.kind == Kind.BOOK;
        boolean author = node != null && (node.kind == Kind.AUTHOR || book)
                && node.authorId != null && !node.authorId.isBlank();
        openAuthorButton.setDisable(!author);
        openBookButton.setDisable(!book);
        downloadButton.setDisable(!book || progressIndicator.isVisible());
        acknowledgeButton.setDisable(node == null || node.kind == Kind.ROOT || progressIndicator.isVisible());
        acknowledgeAllButton.setDisable(progressIndicator.isVisible() || updatesTree.getRoot() == null
                || updatesTree.getRoot().getChildren().isEmpty());
        if (book && node.item != null) {
            detailLabel.setText(node.item.bookTitle() + " — " + node.authorName);
        }
    }

    private void setBusy(boolean busy) {
        progressIndicator.setVisible(busy);
        progressIndicator.setManaged(busy);
        refreshButton.setDisable(busy);
        UpdateTreeNode node = selectedNode();
        downloadButton.setDisable(busy || node == null || node.kind != Kind.BOOK);
        acknowledgeButton.setDisable(busy || node == null || node.kind == Kind.ROOT);
        acknowledgeAllButton.setDisable(busy || updatesTree.getRoot() == null
                || updatesTree.getRoot().getChildren().isEmpty());
    }

    private UpdateTreeNode selectedNode() {
        TreeItem<UpdateTreeNode> selected = updatesTree.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.getValue();
    }


    @Override
    public void dispose() {
        disposed = true;
        loadGeneration++;
    }

    private enum Kind { ROOT, AUTHOR, CATEGORY, BOOK }

    private static final class UpdateTreeNode {
        private final Kind kind;
        private final String authorId;
        private final String authorName;
        private final CatalogUpdateType type;
        private final int count;
        private final CatalogUpdateItem item;

        private UpdateTreeNode(Kind kind, String authorId, String authorName, CatalogUpdateType type, int count, CatalogUpdateItem item) {
            this.kind = kind;
            this.authorId = authorId == null ? "" : authorId;
            this.authorName = authorName == null ? "" : authorName;
            this.type = type;
            this.count = count;
            this.item = item;
        }

        static UpdateTreeNode root() { return new UpdateTreeNode(Kind.ROOT, "", "", null, 0, null); }
        static UpdateTreeNode author(CatalogUpdateAuthorGroup group) {
            return new UpdateTreeNode(Kind.AUTHOR, group.authorId(), group.authorName(), null, (int) group.totalCount(), null);
        }
        static UpdateTreeNode category(CatalogUpdateAuthorGroup group, CatalogUpdateType type, int count) {
            return new UpdateTreeNode(Kind.CATEGORY, group.authorId(), group.authorName(), type, count, null);
        }
        static UpdateTreeNode book(CatalogUpdateAuthorGroup group, CatalogUpdateItem item) {
            return new UpdateTreeNode(Kind.BOOK, group.authorId(), group.authorName(), item.type(), 1, item);
        }

        UpdateTreeNode authorNode() {
            return authorId.isBlank() ? null : new UpdateTreeNode(Kind.AUTHOR, authorId, authorName, null, 0, null);
        }

        String displayText(LocalizationService i18n) {
            return switch (kind) {
                case ROOT -> i18n.tr("Оновлення");
                case AUTHOR -> authorName + " (" + count + ")";
                case CATEGORY -> i18n.tr(type == CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR
                        ? "Нові книги" : "Оновлені книги") + " (" + count + ")";
                case BOOK -> item.bookTitle();
            };
        }
    }
}