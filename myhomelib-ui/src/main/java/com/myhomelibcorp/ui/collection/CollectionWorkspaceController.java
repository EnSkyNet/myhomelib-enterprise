package com.myhomelibcorp.ui.collection;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.usecase.collection.*;
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

    private final LoadCollectionsUseCase loadCollectionsUseCase;
    private final LoadCollectionBooksUseCase loadCollectionBooksUseCase;
    private final IsBookInCollectionUseCase isBookInCollectionUseCase;
    private final AddBookToCollectionUseCase addBookToCollectionUseCase;
    private final RemoveBookFromCollectionUseCase removeBookFromCollectionUseCase;
    private final CreateCollectionUseCase createCollectionUseCase;
    private final RenameCollectionUseCase renameCollectionUseCase;
    private final DeleteCollectionUseCase deleteCollectionUseCase;
    private final NavigationService navigationService;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final BookViewModelMapper bookViewModelMapper;

    @FXML private ListView<CollectionDto> collectionsListView;
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

    private CollectionDto currentCollection;
    private final ObservableList<CollectionDto> collectionList = FXCollections.observableArrayList();
    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());

        booksTableView.setItems(books);
        collectionsListView.setItems(collectionList);

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
            List<CollectionDto> collections = loadCollectionsUseCase.execute();
            collectionList.setAll(collections);
            log.info("Завантажено {} колекцій", collections.size());
            if (!collections.isEmpty()) {
                collectionsListView.getSelectionModel().selectFirst();
            } else {
                collectionDetailsBox.setVisible(false);
            }
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій", e);
            dialogService.showError("Помилка", "Не вдалося завантажити колекції: " + e.getMessage());
        }
    }

    private void loadCollectionBooks(CollectionDto collection) {
        List<BookListItem> items = loadCollectionBooksUseCase.execute(collection.getId());
        List<BookViewModel> vms = items.stream()
                .map(bookViewModelMapper::toViewModel)
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

        boolean inCollection = isBookInCollectionUseCase.execute(currentCollection.getId(), selectedBook.getId());
        if (inCollection) {
            dialogService.showWarning("Вже є", "Ця книга вже в колекції \"" + currentCollection.getName() + "\".");
            return;
        }

        try {
            addBookToCollectionUseCase.execute(currentCollection.getId(), selectedBook.getId());
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
                removeBookFromCollectionUseCase.execute(currentCollection.getId(), selected.getId());
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
                    com.myhomelibcorp.domain.model.collection.Collection collection =
                            createCollectionUseCase.execute(name, null);
                    CollectionDto dto = new CollectionDto(
                            collection.getId(),
                            collection.getName(),
                            true
                    );
                    collectionList.add(dto);
                    collectionsListView.getSelectionModel().select(dto);
                    dialogService.showInfo("Успішно", "Колекцію \"" + name + "\" створено");
                    log.info("Колекцію створено: id={}, name={}", collection.getId(), collection.getName());
                } catch (Exception e) {
                    log.error("Помилка створення колекції", e);
                    dialogService.showError("Помилка", "Не вдалося створити колекцію: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void onRenameCollection() {
        CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
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
                    com.myhomelibcorp.domain.model.collection.Collection renamed =
                            renameCollectionUseCase.execute(selected.getId(), newName);
                    CollectionDto updated = new CollectionDto(
                            renamed.getId(),
                            renamed.getName(),
                            true
                    );
                    int index = collectionList.indexOf(selected);
                    if (index >= 0) {
                        collectionList.set(index, updated);
                    }
                    collectionsListView.getSelectionModel().select(updated);
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
        CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
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
                deleteCollectionUseCase.execute(selected.getId());
                collectionList.remove(selected);
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

    public void refresh() {
        loadCollections();
    }
}