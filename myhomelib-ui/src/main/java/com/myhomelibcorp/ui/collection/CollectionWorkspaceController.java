package com.myhomelibcorp.ui.collection;

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
public class CollectionWorkspaceController {

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

    @FXML private ListView<Group> collectionsListView;
    @FXML private Label collectionNameLabel;
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
    @FXML private VBox collectionDetailsBox;

    private Group currentCollection;
    private final ObservableList<Group> groupList = FXCollections.observableArrayList();
    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        // Налаштування колонок таблиці
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());

        booksTableView.setItems(books);
        collectionsListView.setItems(groupList);   // <-- Явна прив'язка

        // Слухач вибору колекції
        collectionsListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                currentCollection = selected;
                loadCollectionBooks(selected);
                collectionDetailsBox.setVisible(true);
                log.info("Вибрано колекцію: {}", selected.getName());
            } else {
                collectionDetailsBox.setVisible(false);
            }
        });

        // Подвійний клік по книзі – навігація
        booksTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToBook(BookId.fromString(selected.getId()));
                }
            }
        });

        loadCollections();
    }

    // Завантаження всіх колекцій
    public void loadCollections() {
        try {
            List<Group> groups = groupRepository.findAll();
            groupList.setAll(groups);
            log.info("Завантажено {} колекцій", groups.size());
            if (!groups.isEmpty()) {
                collectionsListView.getSelectionModel().selectFirst();
            } else {
                collectionDetailsBox.setVisible(false);
            }
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій", e);
            dialogService.showError("Помилка", "Не вдалося завантажити колекції: " + e.getMessage());
        }
    }

    // Завантаження книг для вибраної колекції
    private void loadCollectionBooks(Group collection) {
        List<String> bookIds = groupRepository.findBookIdsByGroup(collection.getId().asLong());
        if (bookIds.isEmpty()) {
            books.clear();
            collectionNameLabel.setText(collection.getName());
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
        collectionNameLabel.setText(collection.getName());
        booksCountLabel.setText(vms.size() + " книг");
        log.info("Завантажено {} книг для колекції {}", vms.size(), collection.getName());
    }

    // ---- Дії з колекціями ----

    @FXML
    private void onAddBookToCollection() {
        BookViewModel selectedBook = appState.getBookTable().getSelectedBook();
        if (selectedBook == null) {
            dialogService.showWarning("Немає книги", "Будь ласка, виберіть книгу в головній таблиці.");
            return;
        }

        if (currentCollection == null) {
            dialogService.showWarning("Немає колекції", "Будь ласка, виберіть колекцію зліва.");
            return;
        }

        List<String> bookIds = groupRepository.findBookIdsByGroup(currentCollection.getId().asLong());
        if (bookIds.contains(selectedBook.getId())) {
            dialogService.showWarning("Вже є", "Ця книга вже в колекції \"" + currentCollection.getName() + "\".");
            return;
        }

        try {
            addBookToGroupUseCase.execute(currentCollection.getId().asLong(), selectedBook.getId());
            loadCollectionBooks(currentCollection);
            dialogService.showInfo("Успішно", "Книгу додано до колекції \"" + currentCollection.getName() + "\".");
        } catch (Exception e) {
            log.error("Помилка додавання книги до колекції", e);
            dialogService.showError("Помилка", "Не вдалося додати книгу: " + e.getMessage());
        }
    }

    @FXML
    private void onRemoveBookFromCollection() {
        if (currentCollection == null) {
            dialogService.showError("Помилка", "Виберіть колекцію");
            return;
        }
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть книгу в таблиці");
            return;
        }
        if (!currentCollection.isAllowDelete()) {
            dialogService.showError("Помилка", "Системну колекцію не можна змінювати");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити книгу з колекції \"" + currentCollection.getName() + "\"?");
        confirm.setContentText(selected.getTitle());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                removeBookFromGroupUseCase.execute(currentCollection.getId().asLong(), selected.getId());
                books.remove(selected);
                booksCountLabel.setText(books.size() + " книг");
                dialogService.showInfo("Успішно", "Книгу видалено з колекції");
            } catch (Exception e) {
                log.error("Помилка видалення книги з колекції", e);
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onCreateCollection() {
        Optional<String> result = dialogService.showTextInput(
                "Створити колекцію",
                "Введіть назву нової колекції",
                "Назва:",
                "");
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    Group group = createGroupUseCase.execute(name);
                    groupList.add(group);
                    collectionsListView.getSelectionModel().select(group);
                    dialogService.showInfo("Успішно", "Колекцію \"" + name + "\" створено");
                    log.info("Колекцію створено: id={}, name={}", group.getId(), group.getName());
                } catch (Exception e) {
                    log.error("Помилка створення колекції", e);
                    dialogService.showError("Помилка", "Не вдалося створити колекцію: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void onRenameCollection() {
        Group selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть колекцію");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Системну колекцію не можна перейменовувати");
            return;
        }
        Optional<String> result = dialogService.showTextInput(
                "Перейменувати колекцію",
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
                    collectionsListView.getSelectionModel().select(renamed);
                    dialogService.showInfo("Успішно", "Колекцію перейменовано на \"" + newName + "\"");
                } catch (Exception e) {
                    log.error("Помилка перейменування колекції", e);
                    dialogService.showError("Помилка", "Не вдалося перейменувати: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void onDeleteCollection() {
        Group selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть колекцію");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Системну колекцію не можна видалити");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити колекцію \"" + selected.getName() + "\"?");
        confirm.setContentText("Книги не будуть видалені, лише зв'язки.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                deleteGroupUseCase.execute(selected.getId().asLong());
                groupList.remove(selected);
                collectionDetailsBox.setVisible(false);
                dialogService.showInfo("Успішно", "Колекцію видалено");
            } catch (Exception e) {
                log.error("Помилка видалення колекції", e);
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onRefresh() {
        loadCollections();
        dialogService.showInfo("Оновлення", "Колекції перезавантажено.");
    }

    // Публічний метод для оновлення ззовні (наприклад, після імпорту)
    public void refresh() {
        loadCollections();
    }
}