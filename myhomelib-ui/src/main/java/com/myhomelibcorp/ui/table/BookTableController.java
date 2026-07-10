package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookTableViewModel;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookTableController {

    private final ApplicationState appState;

    @FXML private TableView<BookViewModel> bookTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> authorColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private TableColumn<BookViewModel, String> genresColumn;
    @FXML private TableColumn<BookViewModel, String> rateColumn;
    @FXML private TableColumn<BookViewModel, String> progressColumn;
    @FXML private TableColumn<BookViewModel, String> dateColumn;

    @FXML
    public void initialize() {
        BookTableViewModel vm = appState.getBookTable();

        // Налаштування колонок
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
                // Оновити деталі через окремий сервіс
                // Наприклад: appState.getBookDetails().setCurrentBook(selected.toDto());
            }
        });
    }
}