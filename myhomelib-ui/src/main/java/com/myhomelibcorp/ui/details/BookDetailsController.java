package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookDetailsViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookDetailsController {

    private final ApplicationState appState;
    private final NavigationService navigationService;
    private final CoverPresenter coverPresenter;
    private final BookViewModelMapper viewModelMapper;

    @FXML private ImageView coverImageView;
    @FXML private Label titleLabel;
    @FXML private Label authorsLabel;
    @FXML private Label seriesLabel;
    @FXML private Label genresLabel;
    @FXML private Label languageLabel;
    @FXML private Label yearLabel;
    @FXML private Label publisherLabel;
    @FXML private Label isbnLabel;
    @FXML private TextArea annotationArea;

    private String lastBookId = null;

    @FXML
    public void initialize() {
        log.info("BookDetailsController.initialize() – прив'язка coverPresenter до coverImageView");
        coverPresenter.bind(coverImageView);

        BookDetailsViewModel vm = appState.getBookDetails();
        vm.currentBookProperty().addListener((obs, old, bookDto) -> {
            log.info("BookDetailsController: змінено книгу: old={}, new={}",
                    old != null ? old.getTitle() : "null",
                    bookDto != null ? bookDto.getTitle() : "null");

            // При зміні книги спочатку очищаємо обкладинку
            coverPresenter.clearCover();

            if (bookDto != null) {
                updateUI(bookDto);
                var bookViewModel = viewModelMapper.toViewModel(bookDto);
                log.info("BookDetailsController: виклик coverPresenter.showCover для книги: {}", bookDto.getTitle());
                // Невелика затримка, щоб ImageView встиг очиститися
                javafx.application.Platform.runLater(() -> {
                    coverPresenter.showCover(bookViewModel);
                });
            } else {
                clearUI();
            }
        });

        // Якщо вже є книга при ініціалізації – показати
        BookDto current = vm.getCurrentBook();
        if (current != null) {
            log.info("BookDetailsController: початкова книга: {}", current.getTitle());
            updateUI(current);
            coverPresenter.showCover(viewModelMapper.toViewModel(current));
        }
    }

    private void updateUI(BookDto book) {
        titleLabel.setText(book.getTitle());
        authorsLabel.setText("Автори: " + book.getAuthorsText());
        seriesLabel.setText("Серія: " + (book.getSeries() != null ? book.getSeries() : "—"));
        genresLabel.setText("Жанр: " + book.getGenresText());
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
        genresLabel.setText("Жанр");
        languageLabel.setText("Мова");
        yearLabel.setText("Рік");
        publisherLabel.setText("Видавництво");
        isbnLabel.setText("ISBN");
        annotationArea.setText("");
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
        BookDto book = appState.getBookDetails().getCurrentBook();
        if (book != null) {
            log.info("Редагування книги: {}", book.getTitle());
        }
    }

    @FXML
    private void onOpenFolder() {
        BookDto book = appState.getBookDetails().getCurrentBook();
        if (book != null) {
            navigationService.openBookFolder(book);
        }
    }
}