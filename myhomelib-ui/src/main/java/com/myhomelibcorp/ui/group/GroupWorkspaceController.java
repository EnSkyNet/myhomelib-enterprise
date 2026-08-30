package com.myhomelibcorp.ui.group;

import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.usecase.group.*;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupWorkspaceController {

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

    @FXML private ListView<GroupDto> groupsListView;
    @FXML private Label groupNameLabel;
    @FXML private Label booksCountLabel;
    @FXML private TableView<BookViewModel> booksTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> authorColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private Button previousPageButton;
    @FXML private Button nextPageButton;
    @FXML private Label pageLabel;
    @FXML private VBox groupDetailsBox;

    private final ObservableList<GroupDto> groupList = FXCollections.observableArrayList();
    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();
    private final AtomicLong pageGeneration = new AtomicLong();
    private GroupDto currentGroup;
    private int currentPage;

    @FXML
    public void initialize() {
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());
        booksTableView.setItems(books);
        groupsListView.setItems(groupList);

        groupsListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            currentPage = 0;
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
        loadGroups();
    }

    private void showContextMenu(GroupDto group, double x, double y) {
        ContextMenu menu = new ContextMenu();
        MenuItem selectItem = new MenuItem("Вибрати");
        selectItem.setOnAction(e -> groupsListView.getSelectionModel().select(group));
        MenuItem renameItem = new MenuItem("Перейменувати");
        renameItem.setOnAction(e -> renameGroup(group));
        MenuItem deleteItem = new MenuItem("Видалити");
        deleteItem.setOnAction(e -> deleteGroup(group));
        menu.getItems().addAll(selectItem, renameItem, deleteItem);
        menu.show(groupsListView, x, y);
    }

    public void loadGroups() {
        try {
            List<GroupDto> groups = loadGroupsUseCase.execute();
            groupList.setAll(groups);
            if (!groups.isEmpty()) groupsListView.getSelectionModel().selectFirst();
            else groupDetailsBox.setVisible(false);
        } catch (Exception e) {
            log.error("Помилка завантаження груп", e);
            dialogService.showError("Помилка", "Не вдалося завантажити групи: " + e.getMessage());
        }
    }

    private void loadGroupBooks(GroupDto group) {
        if (group == null) return;
        long generation = pageGeneration.incrementAndGet();
        int requestedPage = Math.max(0, currentPage);
        int offset = requestedPage * PAGE_SIZE;
        setPagingBusy(true);
        executor.submit(() -> loadGroupBooksUseCase.execute(group.getId(), PAGE_SIZE, offset))
                .thenAccept(page -> UiExecutor.runOnUiThread(() -> {
                    if (generation != pageGeneration.get() || currentGroup == null || !currentGroup.getId().equals(group.getId())) return;
                    if (page.content().isEmpty() && requestedPage > 0 && page.totalElements() > 0) {
                        currentPage = Math.max(0, page.totalPages() - 1);
                        loadGroupBooks(group);
                        return;
                    }
                    currentPage = page.currentPage();
                    books.setAll(page.content().stream().map(bookViewModelMapper::toViewModel).toList());
                    groupNameLabel.setText(group.getName());
                    booksCountLabel.setText(page.totalElements() + " книг");
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
        pageLabel.setText("Сторінка " + shownPage + " / " + totalPages);
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
            dialogService.showWarning("Немає книги", "Будь ласка, виберіть книгу в головній таблиці.");
            return;
        }
        if (currentGroup == null) {
            dialogService.showWarning("Немає групи", "Будь ласка, виберіть групу зліва.");
            return;
        }
        if (isBookInGroupUseCase.execute(currentGroup.getId(), selectedBook.getId())) {
            dialogService.showWarning("Вже є", "Ця книга вже в групі \"" + currentGroup.getName() + "\".");
            return;
        }
        try {
            addBookToGroupUseCase.execute(currentGroup.getId(), selectedBook.getId());
            loadGroupBooks(currentGroup);
            dialogService.showInfo("Успішно", "Книгу додано до групи \"" + currentGroup.getName() + "\".");
        } catch (Exception e) {
            log.error("Помилка додавання книги до групи", e);
            dialogService.showError("Помилка", "Не вдалося додати книгу: " + e.getMessage());
        }
    }

    @FXML
    private void onRemoveBookFromGroup() {
        if (currentGroup == null) {
            dialogService.showError("Помилка", "Виберіть групу");
            return;
        }
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть книгу в таблиці");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити книгу з групи \"" + currentGroup.getName() + "\"?");
        confirm.setContentText(selected.getTitle());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                removeBookFromGroupUseCase.execute(currentGroup.getId(), selected.getId());
                loadGroupBooks(currentGroup);
                dialogService.showInfo("Успішно", "Книгу видалено з групи");
            } catch (Exception e) {
                log.error("Помилка видалення книги з групи", e);
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onCreateGroup() {
        Optional<String> result = dialogService.showTextInput("Створити групу", "Введіть назву нової групи", "Назва:", "");
        result.ifPresent(name -> {
            if (name.isBlank()) return;
            try {
                Group group = createGroupUseCase.execute(name);
                GroupDto dto = new GroupDto(group.getId().asLong(), group.getName(), group.isAllowDelete());
                groupList.add(dto);
                groupsListView.getSelectionModel().select(dto);
                dialogService.showInfo("Успішно", "Групу \"" + name + "\" створено");
            } catch (Exception e) {
                log.error("Помилка створення групи", e);
                dialogService.showError("Помилка", "Не вдалося створити групу: " + e.getMessage());
            }
        });
    }

    @FXML
    private void onRenameGroup() {
        GroupDto selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть групу");
            return;
        }
        renameGroup(selected);
    }

    private void renameGroup(GroupDto group) {
        if (!group.isAllowDelete()) {
            dialogService.showError("Помилка", "Системну групу не можна перейменовувати");
            return;
        }
        Optional<String> result = dialogService.showTextInput("Перейменувати групу",
                "Введіть нову назву для \"" + group.getName() + "\"", "Нова назва:", group.getName());
        result.ifPresent(newName -> {
            if (newName.isBlank() || newName.equals(group.getName())) return;
            try {
                Group renamed = renameGroupUseCase.execute(group.getId(), newName);
                GroupDto updated = new GroupDto(renamed.getId().asLong(), renamed.getName(), renamed.isAllowDelete());
                int index = groupList.indexOf(group);
                if (index >= 0) groupList.set(index, updated);
                groupsListView.getSelectionModel().select(updated);
                dialogService.showInfo("Успішно", "Групу перейменовано на \"" + newName + "\"");
            } catch (Exception e) {
                log.error("Помилка перейменування групи", e);
                dialogService.showError("Помилка", "Не вдалося перейменувати: " + e.getMessage());
            }
        });
    }

    @FXML
    private void onDeleteGroup() {
        GroupDto selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть групу");
            return;
        }
        deleteGroup(selected);
    }

    private void deleteGroup(GroupDto group) {
        if (!group.isAllowDelete()) {
            dialogService.showError("Помилка", "Системну групу не можна видалити");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити групу \"" + group.getName() + "\"?");
        confirm.setContentText("Книги не будуть видалені, лише зв'язки.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                deleteGroupUseCase.execute(group.getId());
                groupList.remove(group);
                groupDetailsBox.setVisible(false);
                dialogService.showInfo("Успішно", "Групу видалено");
            } catch (Exception e) {
                log.error("Помилка видалення групи", e);
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
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
        for (GroupDto candidate : groupList) {
            if (candidate.getId().equals(group.getId().asLong())) {
                groupsListView.getSelectionModel().select(candidate);
                break;
            }
        }
    }
}
