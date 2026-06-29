package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.springframework.stereotype.Service;

@Service
public class BookTableService {

    /**
     * Налаштовує колонки таблиці книг.
     */
    public void setupBookTable(TableView<BookDto> tableView) {
        tableView.getColumns().clear();

        TableColumn<BookDto, String> titleCol = new TableColumn<>("Назва");
        titleCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getTitle()));
        titleCol.setPrefWidth(200);

        TableColumn<BookDto, String> authorCol = new TableColumn<>("Автор");
        authorCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getAuthorsText()));
        authorCol.setPrefWidth(150);

        TableColumn<BookDto, String> seriesCol = new TableColumn<>("Серія");
        seriesCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getSeries()));
        seriesCol.setPrefWidth(100);

        TableColumn<BookDto, String> genresCol = new TableColumn<>("Жанри");
        genresCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getGenresText()));
        genresCol.setPrefWidth(100);

        TableColumn<BookDto, Integer> seqCol = new TableColumn<>("№");
        seqCol.setCellValueFactory(cellData -> new SimpleObjectProperty<>(cellData.getValue().getSequenceNumber()));
        seqCol.setPrefWidth(40);

        TableColumn<BookDto, String> langCol = new TableColumn<>("Мова");
        langCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getLanguage()));
        langCol.setPrefWidth(60);

        TableColumn<BookDto, String> sizeCol = new TableColumn<>("Розмір");
        sizeCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getFileSizeFormatted()));
        sizeCol.setPrefWidth(80);

        TableColumn<BookDto, String> rateCol = new TableColumn<>("Оцінка");
        rateCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getRateStars()));
        rateCol.setPrefWidth(80);

        TableColumn<BookDto, String> dateCol = new TableColumn<>("Додано");
        dateCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getUpdateDateFormatted()));
        dateCol.setPrefWidth(100);

        TableColumn<BookDto, String> statusCol = new TableColumn<>("Статус");
        statusCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getLocalStatus()));
        statusCol.setPrefWidth(70);

        TableColumn<BookDto, String> progressCol = new TableColumn<>("Прогрес");
        progressCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getProgressFormatted()));
        progressCol.setPrefWidth(80);

        tableView.getColumns().addAll(
                titleCol, authorCol, seriesCol, genresCol, seqCol,
                langCol, sizeCol, rateCol, dateCol, statusCol, progressCol
        );
    }
}