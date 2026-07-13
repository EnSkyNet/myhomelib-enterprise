package com.myhomelibcorp.ui.collection;

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
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
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
    private ObservableList<BookViewModel> books = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        // Налаштування колонок таблиці
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());

        booksTableView.setItems(books);

        // Обробка вибору колекції
        collectionsListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                currentCollection = selected;
                loadCollectionBooks(selected);
                collectionDetailsBox.setVisible(true);
            } else {
                collectionDetailsBox.setVisible(false);
            }
        });

        // Подвійний клік по книзі
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

    public void loadCollections() {
        try {
            List<Group> groups = groupRepository.findAll();
            collectionsListView.getItems().setAll(groups);
            if (!groups.isEmpty()) {
                collectionsListView.getSelectionModel().selectFirst();
            } else {
                collectionDetailsBox.setVisible(false);
                // Показати підказку створити колекцію
            }
        } catch (Exception e) {
            log.error("Failed to load collections", e);
            showAlert("Помилка", "Не вдалося завантажити колекції: " + e.getMessage());
        }
    }

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
    }

    @FXML
    private void onCreateCollection() {
        Optional<String> result = showTextInput("Створити колекцію", "Введіть назву нової колекції", "Назва:");
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    Group group = createGroupUseCase.execute(name);
                    collectionsListView.getItems().add(group);
                    collectionsListView.getSelectionModel().select(group);
                    showAlert("Успіх", "Колекцію \"" + name + "\" створено");
                } catch (Exception e) {
                    showAlert("Помилка", "Не вдалося створити колекцію: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void onRenameCollection() {
        Group selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("Помилка", "Виберіть колекцію");
            return;
        }
        if (!selected.isAllowDelete()) {
            showAlert("Помилка", "Системну колекцію не можна перейменовувати");
            return;
        }
        Optional<String> result = showTextInput("Перейменувати колекцію",
                "Введіть нову назву для \"" + selected.getName() + "\"", selected.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                try {
                    Group renamed = renameGroupUseCase.execute(selected.getId().asLong(), newName);
                    int index = collectionsListView.getItems().indexOf(selected);
                    if (index >= 0) {
                        collectionsListView.getItems().set(index, renamed);
                    }
                    collectionsListView.getSelectionModel().select(renamed);
                    showAlert("Успіх", "Колекцію перейменовано на \"" + newName + "\"");
                } catch (Exception e) {
                    showAlert("Помилка", "Не вдалося перейменувати: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void onDeleteCollection() {
        Group selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("Помилка", "Виберіть колекцію");
            return;
        }
        if (!selected.isAllowDelete()) {
            showAlert("Помилка", "Системну колекцію не можна видалити");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити колекцію \"" + selected.getName() + "\"?");
        confirm.setContentText("Книги не будуть видалені, тільки зв'язок.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                deleteGroupUseCase.execute(selected.getId().asLong());
                collectionsListView.getItems().remove(selected);
                collectionDetailsBox.setVisible(false);
                showAlert("Успіх", "Колекцію видалено");
            } catch (Exception e) {
                showAlert("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onAddBookToCollection() {
        // Цей метод викликається з BookWorkspaceController
    }

    @FXML
    private void onRemoveBookFromCollection() {
        if (currentCollection == null) {
            showAlert("Помилка", "Виберіть колекцію");
            return;
        }
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("Помилка", "Виберіть книгу");
            return;
        }
        if (!currentCollection.isAllowDelete()) {
            showAlert("Помилка", "З системної колекції не можна видаляти книги вручну");
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
                showAlert("Успіх", "Книгу видалено з колекції");
            } catch (Exception e) {
                showAlert("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onRefresh() {
        loadCollections();
    }

    private Optional<String> showTextInput(String title, String header, String content) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(content);
        return dialog.showAndWait();
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
