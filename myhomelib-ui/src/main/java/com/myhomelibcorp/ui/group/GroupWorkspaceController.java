package com.myhomelibcorp.ui.group;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.usecase.group.*;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
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
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupWorkspaceController {

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

    @FXML private ListView<GroupDto> groupsListView;
    @FXML private Label groupNameLabel;
    @FXML private Label booksCountLabel;
    @FXML private TableView<BookViewModel> booksTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> authorColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private Button addBookButton;
    @FXML private Button removeBookButton;
    @FXML private Button renameButton;
    @FXML private Button deleteButton;
    @FXML private Button createButton;
    @FXML private VBox groupDetailsBox;

    private GroupDto currentGroup;
    private final ObservableList<GroupDto> groupList = FXCollections.observableArrayList();
    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());

        booksTableView.setItems(books);
        groupsListView.setItems(groupList);

        groupsListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                currentGroup = selected;
                appState.setCurrentGroup(null); // поки немає GroupDto -> Group, але можна конвертувати
                loadGroupBooks(selected);
                groupDetailsBox.setVisible(true);
                log.info("Вибрано групу: {}", selected.getName());
            } else {
                groupDetailsBox.setVisible(false);
            }
        });

        booksTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToBook(BookId.fromString(selected.getId()));
                }
            }
        });

        groupsListView.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            GroupDto selected = groupsListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showContextMenu(selected, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });

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
            log.info("Завантажено {} груп", groups.size());
            if (!groups.isEmpty()) {
                groupsListView.getSelectionModel().selectFirst();
            } else {
                groupDetailsBox.setVisible(false);
            }
        } catch (Exception e) {
            log.error("Помилка завантаження груп", e);
            dialogService.showError("Помилка", "Не вдалося завантажити групи: " + e.getMessage());
        }
    }

    private void loadGroupBooks(GroupDto group) {
        List<BookListItem> items = loadGroupBooksUseCase.execute(group.getId());
        List<BookViewModel> vms = items.stream()
                .map(bookViewModelMapper::toViewModel)
                .collect(Collectors.toList());
        books.setAll(vms);
        groupNameLabel.setText(group.getName());
        booksCountLabel.setText(vms.size() + " книг");
        log.info("Завантажено {} книг для групи {}", vms.size(), group.getName());
    }

    // ---- Дії з групами ----

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

        boolean inGroup = isBookInGroupUseCase.execute(currentGroup.getId(), selectedBook.getId());
        if (inGroup) {
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
                books.remove(selected);
                booksCountLabel.setText(books.size() + " книг");
                dialogService.showInfo("Успішно", "Книгу видалено з групи");
            } catch (Exception e) {
                log.error("Помилка видалення книги з групи", e);
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onCreateGroup() {
        Optional<String> result = dialogService.showTextInput(
                "Створити групу",
                "Введіть назву нової групи",
                "Назва:",
                "");
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    com.myhomelibcorp.domain.model.group.Group group = createGroupUseCase.execute(name);
                    // Конвертуємо в DTO і додаємо до списку
                    GroupDto dto = new GroupDto(group.getId().asLong(), group.getName(), group.isAllowDelete());
                    groupList.add(dto);
                    groupsListView.getSelectionModel().select(dto);
                    dialogService.showInfo("Успішно", "Групу \"" + name + "\" створено");
                    log.info("Створено групу: id={}, name={}", group.getId(), group.getName());
                } catch (Exception e) {
                    log.error("Помилка створення групи", e);
                    dialogService.showError("Помилка", "Не вдалося створити групу: " + e.getMessage());
                }
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
        Optional<String> result = dialogService.showTextInput(
                "Перейменувати групу",
                "Введіть нову назву для \"" + group.getName() + "\"",
                "Нова назва:",
                group.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(group.getName())) {
                try {
                    com.myhomelibcorp.domain.model.group.Group renamed = renameGroupUseCase.execute(group.getId(), newName);
                    GroupDto updated = new GroupDto(renamed.getId().asLong(), renamed.getName(), renamed.isAllowDelete());
                    int index = groupList.indexOf(group);
                    if (index >= 0) {
                        groupList.set(index, updated);
                    }
                    groupsListView.getSelectionModel().select(updated);
                    dialogService.showInfo("Успішно", "Групу перейменовано на \"" + newName + "\"");
                } catch (Exception e) {
                    log.error("Помилка перейменування групи", e);
                    dialogService.showError("Помилка", "Не вдалося перейменувати: " + e.getMessage());
                }
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
        dialogService.showInfo("Оновлення", "Групи перезавантажено.");
    }

    public void refresh() {
        loadGroups();
    }

    public void setGroup(com.myhomelibcorp.domain.model.group.Group group) {
        if (group != null) {
            // Конвертуємо в DTO та вибираємо
            GroupDto dto = new GroupDto(group.getId().asLong(), group.getName(), group.isAllowDelete());
            // Пошук у списку і вибір
            for (GroupDto g : groupList) {
                if (g.getId().equals(dto.getId())) {
                    groupsListView.getSelectionModel().select(g);
                    break;
                }
            }
        }
    }
}