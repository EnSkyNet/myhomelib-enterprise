package com.myhomelibcorp.ui.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.group.AddBookToGroupUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookWorkspaceController {

    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final LoadGroupsUseCase loadGroupsUseCase;
    private final AddBookToGroupUseCase addBookToGroupUseCase;
    private final CoverPresenter coverPresenter;
    private final NavigationService navigationService;
    private final ApplicationState appState;
    private final BookViewModelMapper bookViewModelMapper;
    private final SessionService sessionService;
    private final DialogService dialogService;

    @FXML private ImageView coverImageView;
    @FXML private Label titleLabel;
    @FXML private Label authorLabel;
    @FXML private Label seriesLabel;
    @FXML private Label genresLabel;
    @FXML private Label languageLabel;
    @FXML private Label yearLabel;
    @FXML private Label publisherLabel;
    @FXML private Label isbnLabel;
    @FXML private Label formatLabel;
    @FXML private Label sizeLabel;
    @FXML private Label ratingLabel;
    @FXML private Label pagesLabel;
    @FXML private TextArea annotationArea;
    @FXML private ProgressBar readingProgress;
    @FXML private Label progressLabel;

    private BookDto currentBook;

    @FXML
    public void initialize() {
        coverPresenter.bind(coverImageView);
    }

    public void setBookId(BookId bookId) {
        sessionService.saveLastOpenedBookId(bookId.asString());

        loadBookByIdUseCase.execute(bookId).ifPresentOrElse(book -> {
            currentBook = book;
            UiExecutor.runOnUiThread(() -> {
                updateUI(currentBook);
                coverPresenter.showCover(bookViewModelMapper.toViewModel(currentBook));
            });
        }, () -> {
            log.warn("Book not found: {}", bookId);
            UiExecutor.runOnUiThread(this::clearUI);
        });
    }

    private void updateUI(BookDto book) {
        titleLabel.setText(book.getTitle());
        authorLabel.setText("Автор: " + book.getAuthorsText());
        seriesLabel.setText("Серія: " + (book.getSeries() != null ? book.getSeries() : "—"));
        genresLabel.setText("Жанри: " + book.getGenresText());
        languageLabel.setText("Мова: " + book.getLanguage());
        yearLabel.setText("Рік: " + (book.getYear() != null && book.getYear() > 0 ? String.valueOf(book.getYear()) : "—"));
        publisherLabel.setText("Видавництво: " + (book.getPublisher() != null ? book.getPublisher() : "—"));
        isbnLabel.setText("ISBN: " + (book.getIsbn() != null ? book.getIsbn() : "—"));
        formatLabel.setText("Формат: " + (book.getFileName() != null ? book.getFileName().substring(book.getFileName().lastIndexOf('.')) : "—"));
        sizeLabel.setText("Розмір: " + book.getFileSizeFormatted());
        ratingLabel.setText("Рейтинг: " + book.getRateStars());
        pagesLabel.setText("Сторінок: —");
        annotationArea.setText(book.getAnnotation() != null ? book.getAnnotation() : "");
        readingProgress.setProgress(book.getProgress() / 100.0);
        progressLabel.setText(book.getProgress() + "%");
    }

    private void clearUI() {
        titleLabel.setText("Назва");
        authorLabel.setText("Автор");
        seriesLabel.setText("Серія");
        genresLabel.setText("Жанри");
        languageLabel.setText("Мова");
        yearLabel.setText("Рік");
        publisherLabel.setText("Видавництво");
        isbnLabel.setText("ISBN");
        formatLabel.setText("Формат");
        sizeLabel.setText("Розмір");
        ratingLabel.setText("Рейтинг");
        pagesLabel.setText("Сторінок");
        annotationArea.setText("");
        readingProgress.setProgress(0);
        progressLabel.setText("0%");
        coverPresenter.clearCover();
    }

    @FXML
    private void onOpen() {
        if (currentBook != null) {
            navigationService.openBookFile(currentBook);
        }
    }

    @FXML
    private void onRead() {
        if (currentBook != null) {
            navigationService.readBook(currentBook);
        }
    }

    @FXML
    private void onEdit() {
        if (currentBook != null) {
            showEditDialog();
        }
    }

    @FXML
    private void onOpenFolder() {
        if (currentBook != null) {
            navigationService.openBookFolder(currentBook);
        }
    }

    @FXML
    private void onAddToCollection() {
        if (currentBook == null) {
            dialogService.showWarning("Немає книги", "Спочатку відкрийте книгу.");
            return;
        }
        var groups = loadGroupsUseCase.execute();
        if (groups.isEmpty()) {
            dialogService.showWarning("Немає колекцій", "Створіть колекцію перед додаванням.");
            return;
        }
        Optional<com.myhomelibcorp.application.dto.GroupDto> selected = dialogService.showChoiceDialog(
                groups,
                groups.get(0),
                "Додати до колекції",
                "Виберіть колекцію для книги \"" + currentBook.getTitle() + "\"",
                "Колекція:"
        );
        selected.ifPresent(group -> {
            try {
                addBookToGroupUseCase.execute(group.getId(), currentBook.getId());
                dialogService.showInfo("Успішно", "Книгу додано до колекції \"" + group.getName() + "\".");
                log.info("Книгу {} додано до колекції {}", currentBook.getId(), group.getId());
            } catch (Exception e) {
                log.error("Помилка додавання книги до колекції", e);
                dialogService.showError("Помилка", "Не вдалося додати книгу: " + e.getMessage());
            }
        });
    }

    @FXML
    private void onDeleteBook() {
        if (currentBook != null) {
            dialogService.showInfo("Інформація", "Функція видалення книги в розробці.");
        }
    }

    private void showEditDialog() {
        TextInputDialog dialog = new TextInputDialog(currentBook.getTitle());
        dialog.setTitle("Редагування книги");
        dialog.setHeaderText("Змініть назву книги");
        dialog.setContentText("Нова назва:");
        dialog.showAndWait().ifPresent(newTitle -> {
            if (!newTitle.isBlank() && !newTitle.equals(currentBook.getTitle())) {
                currentBook.setTitle(newTitle);
                titleLabel.setText(newTitle);
            }
        });
    }

    @FXML
    private void onBack() {
        navigationService.navigateToAuthor(null);
    }
}