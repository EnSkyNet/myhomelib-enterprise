package com.myhomelibcorp.ui.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.application.usecase.group.AddBookToGroupUseCase;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class BookWorkspaceController {

    private final BookQueryRepository bookQueryRepository;
    private final CoverPresenter coverPresenter;
    private final NavigationService navigationService;
    private final ApplicationState appState;
    private final BookMapper bookMapper;
    private final BookViewModelMapper bookViewModelMapper;
    private final SessionService sessionService;
    private final GroupRepository groupRepository;
    private final AddBookToGroupUseCase addBookToGroupUseCase;

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
        // Запам'ятовуємо відкриту книгу в сесії
        sessionService.saveLastOpenedBookId(Long.parseLong(bookId.asString()));

        bookQueryRepository.findById(bookId).ifPresentOrElse(book -> {
            currentBook = bookMapper.toDto(book);
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
            // Відкрити діалог редагування
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
        if (currentBook == null) return;
        // Отримати список колекцій
        List<Group> groups = groupRepository.findAll();
        if (groups.isEmpty()) {
            showAlert("Немає колекцій", "Створіть колекцію перед додаванням книг.");
            return;
        }
        // Показати діалог вибору
        ChoiceDialog<Group> dialog = new ChoiceDialog<>(groups.get(0), groups);
        dialog.setTitle("Додати до колекції");
        dialog.setHeaderText("Виберіть колекцію для книги \"" + currentBook.getTitle() + "\"");
        dialog.setContentText("Колекція:");
        dialog.showAndWait().ifPresent(group -> {
            addBookToGroupUseCase.execute(group.getId().asLong(), currentBook.getId());
            showAlert("Успіх", "Книгу додано до колекції \"" + group.getName() + "\"");
        });
    }

    @FXML
    private void onDeleteBook() {
        if (currentBook != null) {
            // Підтвердження та видалення
        }
    }

    private void showEditDialog() {
        // Простий діалог редагування метаданих
        TextInputDialog dialog = new TextInputDialog(currentBook.getTitle());
        dialog.setTitle("Редагування книги");
        dialog.setHeaderText("Змініть назву книги");
        dialog.setContentText("Нова назва:");
        dialog.showAndWait().ifPresent(newTitle -> {
            if (!newTitle.isBlank() && !newTitle.equals(currentBook.getTitle())) {
                // Оновити книгу
                // Викликати UpdateBookUseCase
                currentBook.setTitle(newTitle);
                titleLabel.setText(newTitle);
            }
        });
    }

    @FXML
    private void onBack() {
        // Повернутися до попереднього воркспейсу
        // Використати WorkspaceManager
        navigationService.navigateToAuthor(null); // або інша логіка
    }
    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}