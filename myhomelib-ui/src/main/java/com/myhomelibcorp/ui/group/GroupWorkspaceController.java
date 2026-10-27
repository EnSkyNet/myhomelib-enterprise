package com.myhomelibcorp.ui.group;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.usecase.group.*;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.util.UiSubscriptions;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class GroupWorkspaceController implements WorkspaceLifecycle {

    private static final int PAGE_SIZE = 100;

    private final LoadGroupsUseCase loadGroupsUseCase;
    private final LoadGroupBooksUseCase loadGroupBooksUseCase;
    private final IsBookInGroupUseCase isBookInGroupUseCase;
    private final CreateGroupUseCase createGroupUseCase;
    private final RenameGroupUseCase renameGroupUseCase;
    private final DeleteGroupUseCase deleteGroupUseCase;
    private final AddBookToGroupUseCase addBookToGroupUseCase;
    private final RemoveBookFromGroupUseCase removeBookFromGroupUseCase;
    private final NavigationService navigationService;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final BookViewModelMapper bookViewModelMapper;
    private final UiBackgroundExecutor executor;
    private final GroupMetabibExportService portableExporter;

    @FXML private ListView<GroupDto> groupsListView;
    @FXML private Label groupNameLabel;
    @FXML private Label booksCountLabel;
    @FXML private TableView<BookViewModel> booksTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> authorColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private TableColumn<BookViewModel, String> fileSizeColumn;
    @FXML private Button previousPageButton;
    @FXML private Button nextPageButton;
    @FXML private Label pageLabel;
    @FXML private VBox groupDetailsBox;
    @FXML private Label groupsStateLabel;

    private final ObservableList<GroupDto> groupList = FXCollections.observableArrayList();
    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();
    private final AtomicLong pageGeneration = new AtomicLong();
    private final AtomicLong groupListGeneration = new AtomicLong();
    private final UiSubscriptions subscriptions = new UiSubscriptions();
    private GroupDto currentGroup;
    private int currentPage;
    private OptionalLong currentGroupTotal = OptionalLong.empty();
    private Long requestedGroupId;

    @FXML
    public void initialize() {
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());
        fileSizeColumn.setCellValueFactory(cellData -> cellData.getValue().fileSizeFormattedProperty());
        booksTableView.setItems(books);
        groupsListView.setItems(groupList);
        groupsListView.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(GroupDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });

        groupsListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            currentPage = 0;
            currentGroupTotal = OptionalLong.empty();
            pageGeneration.incrementAndGet();
            if (selected != null) {
                currentGroup = selected;
                appState.setCurrentGroup(new Group(GroupId.fromLong(selected.getId()), selected.getName(), selected.isAllowDelete()));
                groupDetailsBox.setVisible(true);
                loadGroupBooks(selected);
            } else {
                currentGroup = null;
                appState.setCurrentGroup(null);
                books.clear();
                groupDetailsBox.setVisible(false);
                updatePageControls(PageResult.empty());
            }
        });

        booksTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
                if (selected != null) navigationService.navigateToBook(BookId.fromString(selected.getId()));
            }
        });
        groupsListView.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            GroupDto selected = groupsListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showContextMenu(selected, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
        updatePageControls(PageResult.empty());
        subscriptions.listen(appState.currentLibraryCollectionProperty(), (obs, oldCollection, newCollection) -> {
            String oldId = oldCollection == null ? null : oldCollection.getId();
            String newId = newCollection == null ? null : newCollection.getId();
            if (!Objects.equals(oldId, newId)) {
                UiAsyncRequestGuard.invalidate(groupListGeneration);
                pageGeneration.incrementAndGet();
                currentGroup = null;
                currentGroupTotal = OptionalLong.empty();
                groupList.clear();
                books.clear();
                groupDetailsBox.setVisible(false);
                loadGroups();
            }
        });
        loadGroups();
    }

    private void showContextMenu(GroupDto group, double x, double y) {
        ContextMenu menu = new ContextMenu();
        MenuItem selectItem = new MenuItem(UiText.text("ui.java.group_workspace_controller.9bee10d20c"));
        selectItem.setOnAction(e -> groupsListView.getSelectionModel().select(group));
        MenuItem renameItem = new MenuItem(UiText.text("ui.java.group_workspace_controller.b4b0846a77"));
        renameItem.setOnAction(e -> renameGroup(group));
        MenuItem exportItem = new MenuItem(UiText.text("ui.group.export_metabib"));
        exportItem.setOnAction(e -> exportGroupMetabib(group));
        MenuItem deleteItem = new MenuItem(UiText.text("common.delete"));
        deleteItem.setOnAction(e -> deleteGroup(group));
        menu.getItems().addAll(selectItem, renameItem, exportItem, deleteItem);
        menu.show(groupsListView, x, y);
    }

    private void exportGroupMetabib(GroupDto group) {
        String expectedCollectionId = currentCollectionId();
        if (expectedCollectionId == null) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.group.export_collection_missing"));
            return;
        }
        javafx.stage.DirectoryChooser folderChooser = new javafx.stage.DirectoryChooser();
        folderChooser.setTitle(UiText.text("ui.group.export_destination"));
        java.io.File selected = folderChooser.showDialog(groupsListView.getScene().getWindow());
        if (selected == null) return;
        // EXPORT scope is acquired before enqueuing to prevent a SWITCH gap.
        executor.submitCollectionScopedOperation(LibraryOperationType.EXPORT,
                () -> portableExporter.export(group.getId(), expectedCollectionId, selected.toPath()))
                .whenComplete((result, failure) -> UiExecutor.runOnUiThread(() -> {
                    if (failure != null) {
                        log.error("Portable metabib group export failed", failure);
                        dialogService.showError(UiText.text("common.error"),
                                UiText.text("ui.group.export_failed") + ": " + UiExceptionSupport.message(failure));
                    } else {
                        dialogService.showInfo(UiText.text("common.success"),
                                UiText.text("ui.group.export_success") + ": " + result.folder()
                                        + " (" + result.exported() + " / " + result.skipped() + ")");
                    }
                }));
    }

    public void loadGroups() {
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(groupListGeneration, appState);
        setGroupsState(UiText.text("ui.fxml.book_workspace.loading"), true, true);
        executor.submitCollectionScoped(LibraryOperationType.QUERY, loadGroupsUseCase::execute).whenComplete((groups, error) ->
                UiExecutor.runOnUiThread(() -> {
                    if (!UiAsyncRequestGuard.isCurrent(requestToken, groupListGeneration, appState)) return;
                    if (error != null) {
                        log.error("Помилка завантаження груп", error);
                        groupList.clear();
                        books.clear();
                        currentGroup = null;
                        groupDetailsBox.setVisible(false);
                        setGroupsState(UiText.text("ui.java.group_workspace_controller.f8e3a3637c"), true, false);
                        return;
                    }
                    List<GroupDto> safeGroups = groups == null ? List.of() : groups;
                    groupList.setAll(safeGroups);
                    if (safeGroups.isEmpty()) {
                        currentGroup = null;
                        books.clear();
                        groupDetailsBox.setVisible(false);
                        setGroupsState(UiText.text("ui.java.group_workspace_controller.6b88e66ffc"), true, false);
                        return;
                    }
                    setGroupsState(null, false, false);
                    if (!selectRequestedGroup()) {
                        groupsListView.getSelectionModel().selectFirst();
                    }
                }));
    }

    private boolean selectRequestedGroup() {
        if (requestedGroupId == null) return false;
        for (GroupDto candidate : groupList) {
            if (Objects.equals(candidate.getId(), requestedGroupId)) {
                groupsListView.getSelectionModel().select(candidate);
                return true;
            }
        }
        return false;
    }

    private void setGroupsState(String text, boolean visible, boolean loading) {
        if (groupsStateLabel != null) {
            groupsStateLabel.setText(text == null ? "" : text);
            groupsStateLabel.setVisible(visible);
            groupsStateLabel.setManaged(visible);
        }
        if (groupsListView != null) groupsListView.setDisable(loading);
    }

    private void loadGroupBooks(GroupDto group) {
        if (group == null) return;
        long generation = pageGeneration.incrementAndGet();
        int requestedPage = Math.max(0, currentPage);
        int offset = requestedPage * PAGE_SIZE;
        setPagingBusy(true);
        OptionalLong knownTotal = currentGroupTotal;
        executor.submitCollectionScoped(LibraryOperationType.QUERY, () -> knownTotal.isPresent()
                        ? loadGroupBooksUseCase.execute(group.getId(), PAGE_SIZE, offset, knownTotal.getAsLong())
                        : loadGroupBooksUseCase.execute(group.getId(), PAGE_SIZE, offset))
                .thenAccept(page -> UiExecutor.runOnUiThread(() -> {
                    if (generation != pageGeneration.get() || currentGroup == null || !currentGroup.getId().equals(group.getId())) return;
                    if (page.content().isEmpty() && requestedPage > 0 && page.totalElements() > 0) {
                        if (knownTotal.isPresent()) {
                            // The membership may have changed outside this workspace. Recount once
                            // before deciding which page is now the last valid page.
                            currentGroupTotal = OptionalLong.empty();
                            loadGroupBooks(group);
                            return;
                        }
                        currentGroupTotal = OptionalLong.of(page.totalElements());
                        currentPage = Math.max(0, page.totalPages() - 1);
                        loadGroupBooks(group);
                        return;
                    }
                    currentPage = page.currentPage();
                    currentGroupTotal = OptionalLong.of(page.totalElements());
                    books.setAll(page.content().stream().map(bookViewModelMapper::toViewModel).toList());
                    groupNameLabel.setText(group.getName());
                    booksCountLabel.setText(page.totalElements() + UiText.text("ui.java.group_workspace_controller.faf0c7e42c"));
                    updatePageControls(page);
                    setPagingBusy(false);
                })).exceptionally(ex -> {
                    log.error("Помилка завантаження книг групи {}", group.getName(), ex);
                    UiExecutor.runOnUiThread(() -> {
                        if (generation == pageGeneration.get()) setPagingBusy(false);
                    });
                    return null;
                });
    }

    private void updatePageControls(PageResult<?> page) {
        int shownPage = page.totalElements() == 0 ? 0 : page.currentPage() + 1;
        int totalPages = page.totalElements() == 0 ? 0 : Math.max(1, page.totalPages());
        pageLabel.setText(UiText.text("ui.java.group_workspace_controller.5b014a3bea") + shownPage + " / " + totalPages);
        previousPageButton.setDisable(!page.hasPrevious());
        nextPageButton.setDisable(!page.hasNext());
    }

    private void setPagingBusy(boolean busy) {
        if (previousPageButton != null) previousPageButton.setDisable(busy || currentPage <= 0);
        if (nextPageButton != null && busy) nextPageButton.setDisable(true);
    }

    @FXML
    private void onPreviousPage() {
        if (currentGroup == null || currentPage <= 0) return;
        currentPage--;
        loadGroupBooks(currentGroup);
    }

    @FXML
    private void onNextPage() {
        if (currentGroup == null) return;
        currentPage++;
        loadGroupBooks(currentGroup);
    }

    @FXML
    private void onAddBookToGroup() {
        BookViewModel selectedBook = appState.getBookTable().getSelectedBook();
        if (selectedBook == null) {
            dialogService.showWarning(UiText.text("ui.java.group_workspace_controller.5c79bfb614"), UiText.text("ui.java.group_workspace_controller.f353a3c1d9"));
            return;
        }
        if (currentGroup == null) {
            dialogService.showWarning(UiText.text("ui.java.group_workspace_controller.36d06fcc7d"), UiText.text("ui.java.group_workspace_controller.a1c52962e8"));
            return;
        }
        String expectedCollectionId = currentCollectionId();
        if (isBookInGroupUseCase.execute(currentGroup.getId(), selectedBook.getId())) {
            dialogService.showWarning(UiText.text("ui.java.group_workspace_controller.ab2db029cf"), UiText.text("ui.java.group_workspace_controller.81f9f72d59") + currentGroup.getName() + "\".");
            return;
        }
        try {
            addBookToGroupUseCase.execute(currentGroup.getId(), selectedBook.getId(), expectedCollectionId);
            currentGroupTotal = OptionalLong.empty();
            loadGroupBooks(currentGroup);
            dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.group_workspace_controller.279310e5a2") + currentGroup.getName() + "\".");
        } catch (Exception e) {
            log.error("Помилка додавання книги до групи", e);
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.c44c9a561c") + UiExceptionSupport.message(e));
        }
    }

    @FXML
    private void onRemoveBookFromGroup() {
        if (currentGroup == null) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.8fe77eec46"));
            return;
        }
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.9d88dc3684"));
            return;
        }
        String expectedCollectionId = currentCollectionId();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogLocalization.prepare(confirm);
        confirm.setTitle(UiText.text("common.confirmation"));
        confirm.setHeaderText(UiText.text("ui.java.group_workspace_controller.8e26f3c163") + currentGroup.getName() + "\"?");
        confirm.setContentText(selected.getTitle());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                removeBookFromGroupUseCase.execute(currentGroup.getId(), selected.getId(), expectedCollectionId);
                currentGroupTotal = OptionalLong.empty();
                loadGroupBooks(currentGroup);
                dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.group_workspace_controller.c2d4c189d6"));
            } catch (Exception e) {
                log.error("Помилка видалення книги з групи", e);
                dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.7481af0840") + UiExceptionSupport.message(e));
            }
        }
    }

    @FXML
    private void onCreateGroup() {
        String expectedCollectionId = currentCollectionId();
        Optional<String> result = dialogService.showTextInput(UiText.text("ui.fxml.groups_workspace.create_group"), UiText.text("ui.java.group_workspace_controller.ba16e73b9c"), UiText.text("common.name.label"), "");
        result.ifPresent(name -> {
            if (name.isBlank()) return;
            try {
                Group group = createGroupUseCase.execute(name, expectedCollectionId);
                GroupDto dto = new GroupDto(group.getId().asLong(), group.getName(), group.isAllowDelete());
                groupList.add(dto);
                groupsListView.getSelectionModel().select(dto);
                dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.group_workspace_controller.fc9846c1a8") + name + UiText.text("ui.java.group_workspace_controller.0a0b576c6d"));
            } catch (Exception e) {
                log.error("Помилка створення групи", e);
                dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.832684ccce") + UiExceptionSupport.message(e));
            }
        });
    }

    @FXML
    private void onRenameGroup() {
        GroupDto selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.8fe77eec46"));
            return;
        }
        renameGroup(selected);
    }

    private void renameGroup(GroupDto group) {
        if (!group.isAllowDelete()) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.cb59810c30"));
            return;
        }
        String expectedCollectionId = currentCollectionId();
        Optional<String> result = dialogService.showTextInput(UiText.text("ui.fxml.groups_workspace.rename_group"),
                UiText.text("ui.java.group_workspace_controller.0ff4d98614") + group.getName() + "\"", UiText.text("ui.java.group_workspace_controller.db3f487087"), group.getName());
        result.ifPresent(newName -> {
            if (newName.isBlank() || newName.equals(group.getName())) return;
            try {
                Group renamed = renameGroupUseCase.execute(group.getId(), newName, expectedCollectionId);
                GroupDto updated = new GroupDto(renamed.getId().asLong(), renamed.getName(), renamed.isAllowDelete());
                int index = groupList.indexOf(group);
                if (index >= 0) groupList.set(index, updated);
                groupsListView.getSelectionModel().select(updated);
                dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.group_workspace_controller.011e487308") + newName + "\"");
            } catch (Exception e) {
                log.error("Помилка перейменування групи", e);
                dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.d207d46772") + UiExceptionSupport.message(e));
            }
        });
    }

    @FXML
    private void onDeleteGroup() {
        GroupDto selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.8fe77eec46"));
            return;
        }
        deleteGroup(selected);
    }

    private void deleteGroup(GroupDto group) {
        if (!group.isAllowDelete()) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.0ba9c100d8"));
            return;
        }
        String expectedCollectionId = currentCollectionId();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogLocalization.prepare(confirm);
        confirm.setTitle(UiText.text("common.confirmation"));
        confirm.setHeaderText(UiText.text("ui.java.group_workspace_controller.e5db9db9a1") + group.getName() + "\"?");
        confirm.setContentText(UiText.text("ui.java.group_workspace_controller.c23e524406"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                deleteGroupUseCase.execute(group.getId(), expectedCollectionId);
                groupList.remove(group);
                groupDetailsBox.setVisible(false);
                dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.group_workspace_controller.307b3464aa"));
            } catch (Exception e) {
                log.error("Помилка видалення групи", e);
                dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_workspace_controller.7481af0840") + UiExceptionSupport.message(e));
            }
        }
    }

    @FXML
    private void onRefresh() {
        loadGroups();
    }

    public void refresh() {
        loadGroups();
    }

    public void setGroup(Group group) {
        if (group == null) return;
        requestedGroupId = group.getId().asLong();
        selectRequestedGroup();
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null ? null : collection.getId();
    }

    @Override
    public void dispose() {
        UiAsyncRequestGuard.invalidate(groupListGeneration);
        pageGeneration.incrementAndGet();
        subscriptions.close();
        currentGroup = null;
        appState.setCurrentGroup(null);
    }
}
