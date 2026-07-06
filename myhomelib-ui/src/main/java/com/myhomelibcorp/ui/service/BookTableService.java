package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.springframework.stereotype.Service;

@Service
public class BookTableService {

    public void setupBookTable(TableView<BookViewModel> tableView) {
        tableView.getColumns().clear();

        TableColumn<BookViewModel, String> titleCol = new TableColumn<>("Назва");
        titleCol.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        titleCol.setPrefWidth(200);

        TableColumn<BookViewModel, String> authorCol = new TableColumn<>("Автор");
        authorCol.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        authorCol.setPrefWidth(150);

        TableColumn<BookViewModel, String> seriesCol = new TableColumn<>("Серія");
        seriesCol.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());
        seriesCol.setPrefWidth(100);

        TableColumn<BookViewModel, String> genresCol = new TableColumn<>("Жанри");
        genresCol.setCellValueFactory(cellData -> cellData.getValue().genresTextProperty());
        genresCol.setPrefWidth(100);

        TableColumn<BookViewModel, Number> seqCol = new TableColumn<>("№");
        seqCol.setCellValueFactory(cellData -> cellData.getValue().sequenceNumberProperty());
        seqCol.setPrefWidth(40);

        TableColumn<BookViewModel, String> langCol = new TableColumn<>("Мова");
        langCol.setCellValueFactory(cellData -> cellData.getValue().languageProperty());
        langCol.setPrefWidth(60);

        TableColumn<BookViewModel, String> sizeCol = new TableColumn<>("Розмір");
        sizeCol.setCellValueFactory(cellData -> cellData.getValue().fileSizeFormattedProperty());
        sizeCol.setPrefWidth(80);

        TableColumn<BookViewModel, String> rateCol = new TableColumn<>("Оцінка");
        rateCol.setCellValueFactory(cellData -> cellData.getValue().rateStarsProperty());
        rateCol.setPrefWidth(80);

        TableColumn<BookViewModel, String> dateCol = new TableColumn<>("Додано");
        dateCol.setCellValueFactory(cellData -> cellData.getValue().createdAtFormattedProperty());
        dateCol.setPrefWidth(100);

        TableColumn<BookViewModel, String> statusCol = new TableColumn<>("Статус");
        statusCol.setCellValueFactory(cellData -> cellData.getValue().localStatusProperty());
        statusCol.setPrefWidth(70);

        TableColumn<BookViewModel, String> progressCol = new TableColumn<>("Прогрес");
        progressCol.setCellValueFactory(cellData -> cellData.getValue().progressFormattedProperty());
        progressCol.setPrefWidth(80);

        tableView.getColumns().addAll(
                titleCol, authorCol, seriesCol, genresCol, seqCol,
                langCol, sizeCol, rateCol, dateCol, statusCol, progressCol
        );
    }
}