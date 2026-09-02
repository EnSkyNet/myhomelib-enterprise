package com.myhomelibcorp.ui.following;

import com.myhomelibcorp.application.catalog.CatalogUpdateService;
import com.myhomelibcorp.application.catalog.FollowedAuthorSummary;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** End-to-end view of authors explicitly followed by the user. */
@Component
@RequiredArgsConstructor
public class FollowedAuthorsWorkspaceController implements WorkspaceLifecycle {
    private final CatalogUpdateService catalogUpdateService;
    private final NavigationService navigationService;
    private final UiBackgroundExecutor executor;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final LocalizationService localizationService;

    @FXML private TableView<FollowedAuthorSummary> followedTable;
    @FXML private TableColumn<FollowedAuthorSummary, String> authorColumn;
    @FXML private TableColumn<FollowedAuthorSummary, Number> booksColumn;
    @FXML private TableColumn<FollowedAuthorSummary, Number> newColumn;
    @FXML private TableColumn<FollowedAuthorSummary, String> lastBookColumn;
    @FXML private TableColumn<FollowedAuthorSummary, String> dateColumn;
    @FXML private Label summaryLabel;
    @FXML private Label detailLabel;
    @FXML private Label emptyLabel;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button openAuthorButton;
    @FXML private Button acknowledgeButton;
    @FXML private Button unfollowButton;
    @FXML private Button refreshButton;

    private volatile boolean disposed;
    private long loadGeneration;

    @FXML
    public void initialize() {
        disposed = false;
        authorColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().authorName()));
        booksColumn.setCellValueFactory(cell -> new ReadOnlyLongWrapper(cell.getValue().activeBookCount()));
        newColumn.setCellValueFactory(cell -> new ReadOnlyLongWrapper(cell.getValue().newBookCount()));
        lastBookColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().lastBookTitle()));
        dateColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().lastBookDate()));
        followedTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> updateActions(selected));
        followedTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && selected() != null) openAuthor();
        });
        updateActions(null);
        load();
    }

    @FXML
    public void refresh() { load(); }

    @FXML
    public void openAuthor() {
        FollowedAuthorSummary selected = selected();
        if (selected == null || selected.authorId().isBlank()) return;
        navigationService.navigateToAuthor(AuthorId.fromString(selected.authorId()));
    }

    @FXML
    public void openUpdates() {
        navigationService.navigateToUpdates();
    }

    @FXML
    public void acknowledgeAuthor() {
        FollowedAuthorSummary selected = selected();
        if (selected == null || selected.authorId().isBlank()) return;
        runMutation(
                localizationService.tr("Позначення оновлень автора…"),
                () -> catalogUpdateService.acknowledgeAuthorUpdates(AuthorId.fromString(selected.authorId())));
    }

    @FXML
    public void unfollowAuthor() {
        FollowedAuthorSummary selected = selected();
        if (selected == null || selected.authorId().isBlank()) return;
        if (!dialogService.showConfirmation(
                localizationService.tr("Не стежити за автором?"),
                selected.authorName(),
                localizationService.tr("Автор залишиться у бібліотеці, але нові книги більше не створюватимуть події стеження."))) {
            return;
        }
        runMutation(
                localizationService.tr("Оновлення підписки…"),
                () -> catalogUpdateService.setAuthorFollowed(AuthorId.fromString(selected.authorId()), false));
    }

    private void runMutation(String status, Runnable action) {
        setBusy(true);
        detailLabel.setText(status);
        executor.submit(() -> {
            action.run();
            return null;
        }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
            if (disposed) return;
            setBusy(false);
            if (error != null) {
                Throwable cause = UiExceptionSupport.unwrapAsync(error);
                detailLabel.setText(localizationService.tr("Операція не виконана") + ": "
                        + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                return;
            }
            load();
        }));
    }

    private void load() {
        long generation = ++loadGeneration;
        String collectionId = currentCollectionId();
        setBusy(true);
        detailLabel.setText(localizationService.tr("Завантаження списку стеження…"));
        executor.submit(catalogUpdateService::followedAuthors)
                .whenComplete((rows, error) -> UiExecutor.runOnUiThread(() -> {
                    if (disposed || generation != loadGeneration || !collectionId.equals(currentCollectionId())) return;
                    setBusy(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        followedTable.getItems().clear();
                        applyEmptyState(true);
                        summaryLabel.setText(localizationService.tr("Стеження за авторами"));
                        detailLabel.setText(localizationService.tr("Не вдалося завантажити список") + ": "
                                + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                        return;
                    }
                    applyRows(rows == null ? List.of() : rows);
                }));
    }

    private void applyRows(List<FollowedAuthorSummary> rows) {
        followedTable.getItems().setAll(rows);
        long newBooks = rows.stream().mapToLong(FollowedAuthorSummary::newBookCount).sum();
        summaryLabel.setText(String.format("%s: %,d   •   %s: %,d",
                localizationService.tr("Авторів"), rows.size(),
                localizationService.tr("Нових книг"), newBooks));
        boolean empty = rows.isEmpty();
        applyEmptyState(empty);
        detailLabel.setText(empty
                ? localizationService.tr("Ви ще не стежите за авторами. Відкрийте автора та виберіть «Стежити». ")
                : localizationService.tr("Виберіть автора. Лічильник «Нових» оновлюється після online update."));
        updateActions(selected());
    }

    private void applyEmptyState(boolean empty) {
        followedTable.setVisible(!empty);
        followedTable.setManaged(!empty);
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
    }

    private void updateActions(FollowedAuthorSummary selected) {
        boolean hasSelection = selected != null && !progressIndicator.isVisible();
        openAuthorButton.setDisable(!hasSelection);
        acknowledgeButton.setDisable(!hasSelection || selected.newBookCount() <= 0);
        unfollowButton.setDisable(!hasSelection);
    }

    private void setBusy(boolean busy) {
        progressIndicator.setVisible(busy);
        progressIndicator.setManaged(busy);
        refreshButton.setDisable(busy);
        updateActions(selected());
    }

    private FollowedAuthorSummary selected() {
        return followedTable.getSelectionModel().getSelectedItem();
    }

    private String currentCollectionId() {
        var current = appState.getCurrentLibraryCollection();
        return current == null || current.getId() == null ? "" : current.getId();
    }

    @Override
    public void dispose() {
        disposed = true;
        loadGeneration++;
    }
}