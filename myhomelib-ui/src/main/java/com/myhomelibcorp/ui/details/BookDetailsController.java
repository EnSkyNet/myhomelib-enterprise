package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookDetailsViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookDetailsController {

    private final ApplicationState appState;
    private final NavigationService navigationService;

    @FXML private Label titleLabel;
    @FXML private Label authorsLabel;
    @FXML private Label seriesLabel;
    @FXML private Label genresLabel;
    @FXML private Label languageLabel;
    @FXML private Label yearLabel;
    @FXML private Label publisherLabel;
    @FXML private Label isbnLabel;
    @FXML private TextArea annotationArea;

    @FXML
    public void initialize() {
        BookDetailsViewModel vm = appState.getBookDetails();
        vm.currentBookProperty().addListener((obs, old, book) -> {
            if (book != null) {
                updateUI(book);
            } else {
                clearUI();
            }
        });
    }

    private void updateUI(BookDto book) {
        titleLabel.setText(book.getTitle());
        authorsLabel.setText("Автори: " + book.getAuthorsText());
        seriesLabel.setText("Серія: " + (book.getSeries() != null ? book.getSeries() : "—"));
        genresLabel.setText("Жанри: " + book.getGenresText());
        languageLabel.setText("Мова: " + book.getLanguage());
        yearLabel.setText("Рік: " + (book.getYear() != null && book.getYear() > 0 ? String.valueOf(book.getYear()) : "—"));
        publisherLabel.setText("Видавництво: " + (book.getPublisher() != null ? book.getPublisher() : "—"));
        isbnLabel.setText("ISBN: " + (book.getIsbn() != null ? book.getIsbn() : "—"));
        annotationArea.setText(book.getAnnotation() != null ? book.getAnnotation() : "");
    }

    private void clearUI() {
        titleLabel.setText("Назва");
        authorsLabel.setText("Автори");
        seriesLabel.setText("Серія");
        genresLabel.setText("Жанри");
        languageLabel.setText("Мова");
        yearLabel.setText("Рік");
        publisherLabel.setText("Видавництво");
        isbnLabel.setText("ISBN");
        annotationArea.setText("");
    }

    @FXML
    private void onOpen() {
        BookDto book = appState.getBookDetails().getCurrentBook();
        if (book != null) {
            navigationService.openBookFile(book);
        }
    }

    @FXML
    private void onRead() {
        BookDto book = appState.getBookDetails().getCurrentBook();
        if (book != null) {
            navigationService.readBook(book);
        }
    }

    @FXML
    private void onEdit() {
        // відкрити діалог редагування
    }
}