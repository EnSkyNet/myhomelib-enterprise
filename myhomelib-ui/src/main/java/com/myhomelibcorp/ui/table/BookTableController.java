package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookTableViewModel;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookTableController {

    private final ApplicationState appState;
    private final NavigationService navigationService;
    private final com.myhomelibcorp.ui.service.BookLoaderService bookLoaderService;

    @FXML private TableView<BookViewModel> bookTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> authorColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private TableColumn<BookViewModel, String> genresColumn;
    @FXML private TableColumn<BookViewModel, String> rateColumn;
    @FXML private TableColumn<BookViewModel, String> progressColumn;
    @FXML private TableColumn<BookViewModel, String> dateColumn;

    @FXML private Label pageInfoLabel;
    @FXML private Button prevPageButton;
    @FXML private Button nextPageButton;
    @FXML private ComboBox<Integer> pageSizeComboBox;

    @FXML
    public void initialize() {
        // Реєструємо себе в ApplicationState
        appState.setBookTableController(this);
        log.info("BookTableController зареєстровано в ApplicationState.");

        BookTableViewModel vm = appState.getBookTable();

        TableColumn<BookViewModel, Boolean> selectCol = new TableColumn<>("☑");
        selectCol.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        selectCol.setCellFactory(col -> new CheckBoxTableCell<>());
        selectCol.setEditable(true);
        selectCol.setPrefWidth(40);
        selectCol.setResizable(false);
        selectCol.setStyle("-fx-alignment: CENTER;");

        bookTableView.getColumns().add(0, selectCol);
        bookTableView.setEditable(true);

        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());
        genresColumn.setCellValueFactory(cellData -> cellData.getValue().genresTextProperty());
        rateColumn.setCellValueFactory(cellData -> cellData.getValue().rateStarsProperty());
        progressColumn.setCellValueFactory(cellData -> cellData.getValue().progressFormattedProperty());
        dateColumn.setCellValueFactory(cellData -> cellData.getValue().createdAtFormattedProperty());

        bookTableView.setItems(vm.getBooks());

        bookTableView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            vm.setSelectedBook(selected);
            if (selected != null) {
                appState.getBookDetails().setCurrentBook(
                        com.myhomelibcorp.application.dto.BookDto.builder()
                                .id(selected.getId())
                                .title(selected.getTitle())
                                .authorsText(selected.getAuthorsText())
                                .series(selected.getSeries())
                                .genresText(selected.getGenresText())
                                .language(selected.getLanguage())
                                .fileName(selected.getFileName())
                                .folder(selected.getFolder())
                                .archiveEntry(selected.getArchiveEntry())
                                .fileSize(selected.getFileSize())
                                .annotation(selected.getAnnotation())
                                .rate(selected.getRate())
                                .progress(selected.getProgress())
                                .local(selected.isLocal())
                                .collectionRoot(selected.getCollectionRoot())
                                .build()
                );
            }
        });

        bookTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                BookViewModel selected = bookTableView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToBook(BookId.fromString(selected.getId()));
                }
            }
        });

        setupPagination();
    }

    private void setupPagination() {
        BookTableViewModel vm = appState.getBookTable();
        pageSizeComboBox.getItems().addAll(10, 25, 50, 100, 200);
        pageSizeComboBox.setValue(vm.getPageSize());
        pageSizeComboBox.setOnAction(e -> {
            int size = pageSizeComboBox.getValue();
            bookLoaderService.setPageSize(size);
        });

        prevPageButton.setOnAction(e -> bookLoaderService.previousPage());
        nextPageButton.setOnAction(e -> bookLoaderService.nextPage());
        vm.currentPageProperty().addListener((obs, old, page) -> updatePaginationState(vm));
        vm.totalPagesProperty().addListener((obs, old, pages) -> updatePaginationState(vm));
        updatePaginationState(vm);
    }

    private void updatePaginationState(BookTableViewModel vm) {
        int currentPage = vm.getCurrentPage();
        int totalPages = vm.getTotalPages();
        prevPageButton.setDisable(currentPage <= 0);
        nextPageButton.setDisable(currentPage >= totalPages - 1);
        pageInfoLabel.setText(String.format("Сторінка %d з %d", currentPage + 1, Math.max(1, totalPages)));
    }

    public void showColumnChooser() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Колонки таблиці");
        dialog.setHeaderText("Виберіть колонки для відображення");
        VBox box = new VBox(8); box.setPadding(new javafx.geometry.Insets(12));
        java.util.List<TableColumn<BookViewModel, ?>> columns = new java.util.ArrayList<>();
        for (TableColumn<BookViewModel, ?> c : bookTableView.getColumns()) {
            if (c.getText() == null || "☑".equals(c.getText())) continue;
            columns.add(c);
            CheckBox cb = new CheckBox(c.getText()); cb.setSelected(c.isVisible());
            cb.selectedProperty().addListener((o,a,b) -> c.setVisible(b));
            box.getChildren().add(cb);
        }
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    public void refresh() { bookTableView.refresh(); }

    public void loadGroupedBooks(List<BookViewModel> books) {
        if (bookTableView == null) {
            log.error("Спроба завантажити книги в неініціалізовану таблицю!");
            return;
        }

        BookTableViewModel vm = appState.getBookTable();
        Map<String, List<BookViewModel>> grouped = books.stream()
                .collect(Collectors.groupingBy(
                        book -> book.getSeries() != null && !book.getSeries().isBlank() ? book.getSeries() : "Без серії"
                ));

        List<BookViewModel> groupedBooks = new java.util.ArrayList<>();

        grouped.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("Без серії"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<BookViewModel> seriesBooks = entry.getValue().stream()
                            .sorted(Comparator.comparing(BookViewModel::getSequenceNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                            .collect(Collectors.toList());
                    groupedBooks.addAll(seriesBooks);
                });

        if (grouped.containsKey("Без серії")) {
            groupedBooks.addAll(grouped.get("Без серії"));
        }

        vm.setBooks(groupedBooks);
        vm.setTotalElements(groupedBooks.size());
        vm.setTotalPages(1);
        vm.setCurrentPage(0);
        if (!groupedBooks.isEmpty()) {
            vm.setSelectedBook(groupedBooks.get(0));
        }
        log.info("Таблиця оновлена: {} книг (згруповано по серіях)", groupedBooks.size());
    }
}