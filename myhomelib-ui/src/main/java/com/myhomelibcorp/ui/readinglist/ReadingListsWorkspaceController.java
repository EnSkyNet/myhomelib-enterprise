package com.myhomelibcorp.ui.readinglist;

import com.myhomelibcorp.application.readinglist.*;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiSubscriptions;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class ReadingListsWorkspaceController implements WorkspaceLifecycle {
    private final ListReadingListsUseCase listUseCase;
    private final LoadReadingListUseCase loadUseCase;
    private final CreateReadingListUseCase createUseCase;
    private final RenameReadingListUseCase renameUseCase;
    private final DeleteReadingListUseCase deleteUseCase;
    private final AddBooksToReadingListUseCase addBooksUseCase;
    private final RemoveReadingListItemsUseCase removeItemsUseCase;
    private final ReorderReadingListUseCase reorderUseCase;
    private final FindReadingListResumeItemUseCase resumeUseCase;
    private final ApplicationState appState;
    private final BookSelectionService bookSelectionService;
    private final DialogService dialogs;
    private final NavigationService navigation;

    @FXML private ListView<ReadingListDto> listsView;
    @FXML private Label listTitle;
    @FXML private Label listSummary;
    @FXML private VBox detailsBox;
    @FXML private TableView<ReadingListItemDto> itemsTable;
    @FXML private TableColumn<ReadingListItemDto, Number> positionColumn;
    @FXML private TableColumn<ReadingListItemDto, String> titleColumn;
    @FXML private TableColumn<ReadingListItemDto, String> authorColumn;
    @FXML private TableColumn<ReadingListItemDto, String> seriesColumn;
    @FXML private TableColumn<ReadingListItemDto, String> progressColumn;

    private final ObservableList<ReadingListDto> lists = FXCollections.observableArrayList();
    private final ObservableList<ReadingListItemDto> items = FXCollections.observableArrayList();
    private final UiSubscriptions subscriptions = new UiSubscriptions();
    private ReadingListDto current;

    @FXML
    public void initialize() {
        listsView.setItems(lists);
        itemsTable.setItems(items);
        itemsTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        listsView.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(ReadingListDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + " (" + item.itemCount() + ")");
            }
        });
        positionColumn.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().position() + 1));
        titleColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));
        authorColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().authors()));
        seriesColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().series()));
        progressColumn.setCellValueFactory(c -> new SimpleStringProperty(String.format(java.util.Locale.ROOT, "%.1f%%", c.getValue().progress())));

        listsView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            current = selected;
            if (selected == null) {
                items.clear();
                detailsBox.setVisible(false);
                detailsBox.setManaged(false);
            } else loadSelected();
        });
        itemsTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) openSelectedBook();
        });
        itemsTable.setOnKeyPressed(event -> {
            if (event.isAltDown() && event.getCode() == KeyCode.UP) {
                moveSelected(-1);
                event.consume();
            } else if (event.isAltDown() && event.getCode() == KeyCode.DOWN) {
                moveSelected(1);
                event.consume();
            }
        });
        installDragAndDrop();
        subscriptions.listen(appState.currentLibraryCollectionProperty(), (obs, oldValue, newValue) -> {
            if (!Objects.equals(oldValue == null ? null : oldValue.getId(), newValue == null ? null : newValue.getId())) refreshAll();
        });
        refreshAll();
    }

    @FXML private void onCreate() {
        Optional<String> name = dialogs.showTextInput(UiText.text("ui.reading_lists.create"),
                UiText.text("ui.reading_lists.create_prompt"), UiText.text("common.name.label"), "");
        name.filter(v -> !v.isBlank()).ifPresent(value -> runMutation(() -> createUseCase.execute(value, "", collectionId()), true));
    }

    @FXML private void onRename() {
        if (current == null) return;
        Optional<String> name = dialogs.showTextInput(UiText.text("ui.reading_lists.rename"),
                UiText.text("ui.reading_lists.rename_prompt"), UiText.text("common.name.label"), current.name());
        name.filter(v -> !v.isBlank()).ifPresent(value -> runMutation(() -> renameUseCase.execute(
                current.id(), value, collectionId()), true));
    }

    @FXML private void onDelete() {
        if (current == null) return;
        boolean confirmed = dialogs.showConfirmation(
                UiText.text("common.confirmation"),
                UiText.text("ui.reading_lists.delete_confirm") + " “" + current.name() + "”?",
                UiText.text("ui.reading_lists.delete_keeps_books"));
        if (confirmed) {
            runMutation(() -> deleteUseCase.execute(current.id(), collectionId()), true);
        }
    }

    @FXML private void onAddSelectedBook() {
        if (current == null) return;
        List<BookId> selectedIds = bookSelectionService.snapshot();
        if (selectedIds.isEmpty()) {
            var selected = appState.getBookTable().getSelectedBook();
            if (selected != null && selected.getId() != null && !selected.getId().isBlank()) {
                selectedIds = List.of(BookId.fromString(selected.getId()));
            }
        }
        if (selectedIds.isEmpty()) {
            dialogs.showWarning(UiText.text("ui.reading_lists.title"), UiText.text("ui.reading_lists.select_book_first"));
            return;
        }
        List<BookId> booksToAdd = List.copyOf(selectedIds);
        runMutation(() -> addBooksUseCase.execute(current.id(),
                booksToAdd, collectionId()), false);
    }

    @FXML private void onRemove() {
        if (current == null) return;
        List<BookId> selected = itemsTable.getSelectionModel().getSelectedIndices().stream()
                .filter(index -> index >= 0 && index < items.size())
                .map(items::get)
                .map(ReadingListItemDto::bookId)
                .filter(Objects::nonNull)
                .map(BookId::fromString)
                .toList();
        if (selected.isEmpty()) return;
        runMutation(() -> removeItemsUseCase.execute(current.id(), selected, collectionId()), false);
    }

    @FXML private void onMoveUp() { moveSelected(-1); }
    @FXML private void onMoveDown() { moveSelected(1); }

    @FXML private void onResume() {
        if (current == null) return;
        try {
            resumeUseCase.execute(current.id()).ifPresentOrElse(
                    book -> navigation.navigateToBook(BookId.fromString(book.getId())),
                    () -> dialogs.showInfo(UiText.text("ui.reading_lists.title"), UiText.text("ui.reading_lists.completed")));
        } catch (RuntimeException e) { showError(e); }
    }

    private void moveSelected(int delta) {
        ReadingListItemDto selected = itemsTable.getSelectionModel().getSelectedItem();
        if (current == null || selected == null) return;
        int from = items.indexOf(selected);
        int to = from + delta;
        if (to < 0 || to >= items.size()) return;
        List<ReadingListItemDto> reordered = new ArrayList<>(items);
        java.util.Collections.swap(reordered, from, to);
        persistOrder(reordered, selected.bookId());
    }

    private void persistOrder(List<ReadingListItemDto> reordered, String reselectBookId) {
        try {
            reorderUseCase.execute(current.id(), reordered.stream()
                    .map(v -> BookId.fromString(v.bookId())).toList(), collectionId());
            loadSelected();
            items.stream().filter(i -> i.bookId().equals(reselectBookId)).findFirst()
                    .ifPresent(i -> itemsTable.getSelectionModel().select(i));
        } catch (RuntimeException e) { showError(e); }
    }

    private void installDragAndDrop() {
        itemsTable.setRowFactory(table -> {
            TableRow<ReadingListItemDto> row = new TableRow<>();
            row.setOnDragDetected(event -> {
                if (row.isEmpty()) return;
                ClipboardContent content = new ClipboardContent();
                content.putString(row.getItem().bookId());
                row.startDragAndDrop(TransferMode.MOVE).setContent(content);
                event.consume();
            });
            row.setOnDragOver(event -> {
                if (event.getGestureSource() != row && event.getDragboard().hasString()) event.acceptTransferModes(TransferMode.MOVE);
                event.consume();
            });
            row.setOnDragDropped(event -> {
                String bookId = event.getDragboard().getString();
                ReadingListItemDto dragged = items.stream().filter(i -> i.bookId().equals(bookId)).findFirst().orElse(null);
                if (dragged == null) return;
                int from = items.indexOf(dragged);
                int to = row.isEmpty() ? items.size() : row.getIndex();
                List<ReadingListItemDto> reordered = reorderForDrop(items, from, to);
                persistOrder(reordered, bookId);
                event.setDropCompleted(true);
                event.consume();
            });
            return row;
        });
    }

    static List<ReadingListItemDto> reorderForDrop(List<ReadingListItemDto> source, int from, int targetIndex) {
        if (from < 0 || from >= source.size()) return List.copyOf(source);
        List<ReadingListItemDto> reordered = new ArrayList<>(source);
        ReadingListItemDto dragged = reordered.remove(from);
        int to = targetIndex;
        if (from < to) to--;
        reordered.add(Math.max(0, Math.min(to, reordered.size())), dragged);
        return List.copyOf(reordered);
    }

    private void openSelectedBook() {
        ReadingListItemDto selected = itemsTable.getSelectionModel().getSelectedItem();
        if (selected != null) navigation.navigateToBook(BookId.fromString(selected.bookId()));
    }

    private void refreshAll() {
        String selectedId = current == null ? null : current.id();
        try {
            lists.setAll(listUseCase.execute());
            if (lists.isEmpty()) {
                current = null;
                items.clear();
                detailsBox.setVisible(false);
                detailsBox.setManaged(false);
                return;
            }
            lists.stream().filter(v -> Objects.equals(v.id(), selectedId)).findFirst()
                    .ifPresentOrElse(v -> listsView.getSelectionModel().select(v), () -> listsView.getSelectionModel().selectFirst());
        } catch (RuntimeException e) { showError(e); }
    }

    private void loadSelected() {
        if (current == null) return;
        try {
            ReadingListDetailsDto details = loadUseCase.execute(current.id());
            current = details.list();
            items.setAll(details.items());
            listTitle.setText(current.name());
            listSummary.setText(current.itemCount() + " · " + UiText.text("ui.reading_lists.books"));
            detailsBox.setVisible(true);
            detailsBox.setManaged(true);
        } catch (RuntimeException e) { showError(e); }
    }

    private void runMutation(Runnable mutation, boolean reloadLists) {
        List<String> selectedBookIds = reloadLists ? List.of() : itemsTable.getSelectionModel().getSelectedItems().stream()
                .map(ReadingListItemDto::bookId)
                .filter(Objects::nonNull)
                .toList();
        try {
            mutation.run();
            if (reloadLists) {
                refreshAll();
            } else {
                loadSelected();
                updateListCount();
                if (!selectedBookIds.isEmpty()) {
                    itemsTable.getSelectionModel().clearSelection();
                    items.stream()
                            .filter(item -> selectedBookIds.contains(item.bookId()))
                            .forEach(item -> itemsTable.getSelectionModel().select(item));
                }
            }
        } catch (RuntimeException e) { showError(e); }
    }

    private void updateListCount() {
        if (current == null) return;
        ReadingListDto updated = current;
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i).id().equals(updated.id())) {
                lists.set(i, updated);
                current = updated;
                listsView.getSelectionModel().select(i);
                break;
            }
        }
    }

    private void showError(Throwable e) {
        log.error("Reading Lists action failed", e);
        dialogs.showError(UiText.text("common.error"), UiExceptionSupport.message(e));
    }

    private String collectionId() { return appState.getCurrentLibraryCollectionId(); }

    @Override public void dispose() { subscriptions.close(); }
}
