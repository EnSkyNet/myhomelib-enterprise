package com.myhomelibcorp.ui.updates;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.catalog.CatalogUpdateAuthorGroup;
import com.myhomelibcorp.application.catalog.CatalogUpdateItem;
import com.myhomelibcorp.application.catalog.CatalogUpdateService;
import com.myhomelibcorp.application.catalog.CatalogUpdateSnapshot;
import com.myhomelibcorp.application.catalog.CatalogUpdateType;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationType;
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
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
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
    private final ApplicationState applicationState;

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
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        setBusy(true);
        detailLabel.setText(localizationService.text("ui.text.marking_update_as_viewed"));
        executor.submitCollectionScoped(LibraryOperationType.EDIT, () -> {
            requireCurrentCollection(expectedCollectionId);
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
            if (disposed || !sameCollection(expectedCollectionId)) return;
            setBusy(false);
            if (error != null) {
                Throwable cause = UiExceptionSupport.unwrapAsync(error);
                detailLabel.setText(localizationService.text("ui.text.failed_to_mark_the_update") + ": "
                        + UiExceptionSupport.message(cause));
                return;
            }
            loadUpdates();
        }));
    }

    @FXML
    public void acknowledgeAll() {
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        setBusy(true);
        detailLabel.setText(localizationService.text("ui.text.marking_all_updates_as_viewed"));
        executor.submitCollectionScoped(LibraryOperationType.EDIT, () -> {
                    requireCurrentCollection(expectedCollectionId);
                    catalogUpdateService.acknowledgeAllUpdates();
                    return null;
                })
                .whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
                    if (disposed || !sameCollection(expectedCollectionId)) return;
                    setBusy(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        detailLabel.setText(localizationService.text("ui.text.failed_to_mark_the_update") + ": "
                                + UiExceptionSupport.message(cause));
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
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        setBusy(true);
        detailLabel.setText(localizationService.text("ui.text.preparing_download") + " " + item.bookTitle());

        executor.submitCollectionScoped(LibraryOperationType.QUERY, () -> {
                    requireCurrentCollection(expectedCollectionId);
                    return loadBookByIdUseCase.execute(BookId.fromString(item.bookId()))
                            .orElseThrow(() -> new IllegalStateException(UiText.text("ui.java.updates_workspace_controller.7e6db1b645") + item.bookId()));
                })
                .thenCompose(book -> download(item, book, expectedCollectionId))
                .whenComplete((path, error) -> UiExecutor.runOnUiThread(() -> {
                    if (disposed || !sameCollection(expectedCollectionId)) return;
                    setBusy(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        detailLabel.setText(localizationService.text("ui.text.download_error") + ": "
                                + UiExceptionSupport.message(cause));
                        return;
                    }
                    detailLabel.setText(localizationService.text("ui.text.downloaded_update_acknowledged"));
                    navigationPanelController.refreshAll();
                    loadUpdates();
                }));
    }

    private CompletableFuture<java.nio.file.Path> download(
            CatalogUpdateItem item, BookDto book, String expectedCollectionId) {
        if (!sameCollection(expectedCollectionId)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Активна колекція змінилася до початку завантаження оновлення"));
        }
        return item.type() == CatalogUpdateType.UPDATED_DOWNLOADED_BOOK
                ? bookDownloadCoordinator.downloadUpdate(book, expectedCollectionId)
                : bookDownloadCoordinator.ensureLocal(book, expectedCollectionId);
    }

    private void loadUpdates() {
        long generation = ++loadGeneration;
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        setBusy(true);
        emptyLabel.setVisible(false);
        emptyLabel.setManaged(false);
        detailLabel.setText(localizationService.text("ui.text.loading_updates"));
        executor.submitCollectionScoped(LibraryOperationType.QUERY, () -> {
                    requireCurrentCollection(expectedCollectionId);
                    return catalogUpdateService.pendingUpdateSnapshot();
                })
                .whenComplete((snapshot, error) -> UiExecutor.runOnUiThread(() -> {
                    if (disposed || generation != loadGeneration || !sameCollection(expectedCollectionId)) return;
                    setBusy(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        updatesTree.setRoot(new TreeItem<>(UpdateTreeNode.root()));
                        summaryLabel.setText(localizationService.text("ui.text.updates"));
                        detailLabel.setText(localizationService.text("ui.text.failed_to_load_updates") + ": "
                                + UiExceptionSupport.message(cause));
                        return;
                    }
                    applySnapshot(snapshot == null ? CatalogUpdateSnapshot.empty() : snapshot);
                }));
    }

    private void applySnapshot(CatalogUpdateSnapshot snapshot) {
        summaryLabel.setText(String.format("%s: %,d   •   %s: %,d   •   %s: %,d",
                localizationService.text("ui.text.total"), snapshot.totalCount(),
                localizationService.text("ui.text.new_books_d358a89"), snapshot.newCount(),
                localizationService.text("ui.text.updated_books"), snapshot.updatedCount()));

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
            emptyLabel.setText(localizationService.text("ui.text.no_new_catalog_updates"));
            detailLabel.setText(localizationService.text("ui.text.all_downloaded_updates_are_acknowledged"));
        } else {
            detailLabel.setText(localizationService.text("ui.text.select_a_book_to_view_or_download"));
        }
        updateActions(null);
    }

    private String displayAuthor(String value) {
        return displayAuthor(localizationService, value);
    }

    private String displayTitle(String value) {
        return displayTitle(localizationService, value);
    }

    private static String displayAuthor(LocalizationService i18n, String value) {
        return value == null || value.isBlank() ? i18n.text("common.author.unknown") : value;
    }

    private static String displayTitle(LocalizationService i18n, String value) {
        return value == null || value.isBlank() ? i18n.text("common.title.untitled") : value;
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
            detailLabel.setText(displayTitle(node.item.bookTitle()) + " — " + displayAuthor(node.authorName));
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


    private boolean sameCollection(String expectedCollectionId) {
        return expectedCollectionId != null && !expectedCollectionId.isBlank()
                && expectedCollectionId.equals(applicationState.getCurrentLibraryCollectionId());
    }

    private void requireCurrentCollection(String expectedCollectionId) {
        if (!sameCollection(expectedCollectionId)) {
            throw new IllegalStateException("Активна колекція змінилася під час операції оновлень");
        }
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
                case ROOT -> i18n.text("ui.text.updates");
                case AUTHOR -> displayAuthor(i18n, authorName) + " (" + count + ")";
                case CATEGORY -> i18n.tr(type == CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR
                        ? UiText.text("ui.main.fxml.new_books") : UiText.text("ui.text.updated_books")) + " (" + count + ")";
                case BOOK -> displayTitle(i18n, item.bookTitle());
            };
        }
    }
}