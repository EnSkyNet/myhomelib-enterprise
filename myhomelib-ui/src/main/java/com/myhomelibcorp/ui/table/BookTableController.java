package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookTableViewModel;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookTableController {

    private final ApplicationState appState;
    private final NavigationService navigationService;

    @FXML private TableView<BookViewModel> bookTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> authorColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private TableColumn<BookViewModel, String> genresColumn;
    @FXML private TableColumn<BookViewModel, String> rateColumn;
    @FXML private TableColumn<BookViewModel, String> progressColumn;
    @FXML private TableColumn<BookViewModel, String> dateColumn;

    // Пагінація
    @FXML private Label pageInfoLabel;
    @FXML private Button prevPageButton;
    @FXML private Button nextPageButton;
    @FXML private ComboBox<Integer> pageSizeComboBox;

    @FXML
    public void initialize() {
        BookTableViewModel vm = appState.getBookTable();

        // Додаємо колонку з чекбоксами на початок - З БІЛЬШОЮ ШИРИНОЮ
        TableColumn<BookViewModel, Boolean> selectCol = new TableColumn<>("");
        selectCol.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        selectCol.setCellFactory(col -> new CheckBoxTableCell<>());
        selectCol.setEditable(true);
        selectCol.setPrefWidth(150);  // ЗБІЛЬШЕНО
        selectCol.setMaxWidth(150);
        selectCol.setMinWidth(150);
        selectCol.setResizable(false);
        selectCol.setText("☑");  // Додаємо іконку в заголовок
        selectCol.setStyle("-fx-alignment: CENTER; -fx-padding: 0;");

        // Налаштування інших колонок
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());
        genresColumn.setCellValueFactory(cellData -> cellData.getValue().genresTextProperty());
        rateColumn.setCellValueFactory(cellData -> cellData.getValue().rateStarsProperty());
        progressColumn.setCellValueFactory(cellData -> cellData.getValue().progressFormattedProperty());
        dateColumn.setCellValueFactory(cellData -> cellData.getValue().createdAtFormattedProperty());

        // Додаємо колонку першою
        bookTableView.getColumns().add(0, selectCol);
        bookTableView.setEditable(true);

        bookTableView.setItems(vm.getBooks());

        // Виділення книги для деталей
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

        // Подвійний клік для відкриття книги
        bookTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                BookViewModel selected = bookTableView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToBook(BookId.fromString(selected.getId()));
                }
            }
        });

        // Налаштування пагінації
        setupPagination();
    }

    private void setupPagination() {
        BookTableViewModel vm = appState.getBookTable();

        pageSizeComboBox.getItems().addAll(10, 25, 50, 100, 200);
        pageSizeComboBox.setValue(vm.getPageSize());
        pageSizeComboBox.setOnAction(e -> {
            int size = pageSizeComboBox.getValue();
            vm.setPageSize(size);
        });

        prevPageButton.setOnAction(e -> {});
        nextPageButton.setOnAction(e -> {});

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

    public void refresh() {
        bookTableView.refresh();
    }

    public TableView<BookViewModel> getTableView() {
        return bookTableView;
    }
}