package com.myhomelibcorp.ui.group;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.usecase.group.AddBookToGroupUseCase;
import com.myhomelibcorp.application.usecase.group.CreateGroupUseCase;
import com.myhomelibcorp.application.usecase.group.DeleteGroupUseCase;
import com.myhomelibcorp.application.usecase.group.RemoveBookFromGroupUseCase;
import com.myhomelibcorp.application.usecase.group.RenameGroupUseCase;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.group.Group;
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

    private final GroupRepository groupRepository;
    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final BookViewModelMapper bookViewModelMapper;
    private final CreateGroupUseCase createGroupUseCase;
    private final RenameGroupUseCase renameGroupUseCase;
    private final DeleteGroupUseCase deleteGroupUseCase;
    private final AddBookToGroupUseCase addBookToGroupUseCase;
    private final RemoveBookFromGroupUseCase removeBookFromGroupUseCase;
    private final NavigationService navigationService;
    private final ApplicationState appState;
    private final DialogService dialogService;

    @FXML private ListView<Group> groupsListView;
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

    private Group currentGroup;
    private final ObservableList<Group> groupList = FXCollections.observableArrayList();
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
                appState.setCurrentGroup(selected);
                loadGroupBooks(selected);
                groupDetailsBox.setVisible(true);
                log.info("Вибрано групу: {}", selected.getName());
            } else {
                groupDetailsBox.setVisible(false);
            }
        });

        appState.currentGroupProperty().addListener((obs, old, newGroup) -> {
            if (newGroup != null && !newGroup.equals(currentGroup)) {
                groupsListView.getSelectionModel().select(newGroup);
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
            Group selected = groupsListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showContextMenu(selected, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });

        loadGroups();
    }

    private void showContextMenu(Group group, double x, double y) {
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
            List<Group> groups = groupRepository.findAll();
            groupList.setAll(groups);
            log.info("Завантажено {} груп", groups.size());
            if (!groups.isEmpty()) {
                Group current = appState.getCurrentGroup();
                if (current != null && groupList.contains(current)) {
                    groupsListView.getSelectionModel().select(current);
                } else {
                    groupsListView.getSelectionModel().selectFirst();
                }
            } else {
                groupDetailsBox.setVisible(false);
            }
        } catch (Exception e) {
            log.error("Помилка завантаження груп", e);
            dialogService.showError("Помилка", "Не вдалося завантажити групи: " + e.getMessage());
        }
    }

    private void loadGroupBooks(Group group) {
        List<String> bookIds = groupRepository.findBookIdsByGroup(group.getId().asLong());
        if (bookIds.isEmpty()) {
            books.clear();
            groupNameLabel.setText(group.getName());
            booksCountLabel.setText("0 книг");
            return;
        }

        List<BookId> ids = bookIds.stream()
                .map(BookId::fromString)
                .collect(Collectors.toList());

        List<Book> foundBooks = bookQueryRepository.findByIds(ids);
        List<BookViewModel> vms = foundBooks.stream()
                .map(book -> bookViewModelMapper.toViewModel(bookMapper.toDto(book)))
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

        List<String> bookIds = groupRepository.findBookIdsByGroup(currentGroup.getId().asLong());
        if (bookIds.contains(selectedBook.getId())) {
            dialogService.showWarning("Вже є", "Ця книга вже в групі \"" + currentGroup.getName() + "\".");
            return;
        }

        try {
            addBookToGroupUseCase.execute(currentGroup.getId().asLong(), selectedBook.getId());
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

        // <-- ПРИБРАНО ПЕРЕВІРКУ allowDelete
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити книгу з групи \"" + currentGroup.getName() + "\"?");
        confirm.setContentText(selected.getTitle());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                removeBookFromGroupUseCase.execute(currentGroup.getId().asLong(), selected.getId());
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
                    Group group = createGroupUseCase.execute(name);
                    groupList.add(group);
                    groupsListView.getSelectionModel().select(group);
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
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть групу");
            return;
        }
        renameGroup(selected);
    }

    private void renameGroup(Group selected) {
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Системну групу не можна перейменовувати");
            return;
        }
        Optional<String> result = dialogService.showTextInput(
                "Перейменувати групу",
                "Введіть нову назву для \"" + selected.getName() + "\"",
                "Нова назва:",
                selected.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                try {
                    Group renamed = renameGroupUseCase.execute(selected.getId().asLong(), newName);
                    int index = groupList.indexOf(selected);
                    if (index >= 0) {
                        groupList.set(index, renamed);
                    }
                    groupsListView.getSelectionModel().select(renamed);
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
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть групу");
            return;
        }
        deleteGroup(selected);
    }

    private void deleteGroup(Group selected) {
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Системну групу не можна видалити");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити групу \"" + selected.getName() + "\"?");
        confirm.setContentText("Книги не будуть видалені, лише зв'язки.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                deleteGroupUseCase.execute(selected.getId().asLong());
                groupList.remove(selected);
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

    public void setGroup(Group group) {
        if (group != null) {
            appState.setCurrentGroup(group);
            loadGroups();
        }
    }
}