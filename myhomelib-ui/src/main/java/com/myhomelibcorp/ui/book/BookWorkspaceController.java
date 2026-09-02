package com.myhomelibcorp.ui.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.group.AddBookToGroupUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.ClassicLibraryActionsService;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    private final BookViewModelMapper bookViewModelMapper;
    private final DialogService dialogService;
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final BookSaver bookSaver;
    private final ClassicLibraryActionsService classicLibraryActionsService;

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
    @FXML private Label annotationLabel;
    @FXML private ProgressBar readingProgress;
    @FXML private Label progressLabel;

    private BookDto currentBook;

    @FXML
    public void initialize() {
        coverPresenter.bind(coverImageView);
    }

    public void setBookId(BookId bookId) {
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
        String localizedGenres = bookViewModelMapper.toViewModel(book).getGenresText();
        genresLabel.setText("Жанри: " + (localizedGenres == null || localizedGenres.isBlank() ? "—" : localizedGenres));
        languageLabel.setText("Мова: " + book.getLanguage());
        yearLabel.setText("Рік: " + (book.getYear() != null && book.getYear() > 0 ? String.valueOf(book.getYear()) : "—"));
        publisherLabel.setText("Видавництво: " + (book.getPublisher() != null ? book.getPublisher() : "—"));
        isbnLabel.setText("ISBN: " + (book.getIsbn() != null ? book.getIsbn() : "—"));
        formatLabel.setText("Формат: " + displayFormat(book.getFileName(), book.getArchiveEntry()));
        sizeLabel.setText("Розмір: " + book.getFileSizeFormatted());
        ratingLabel.setText("Рейтинг: " + book.getRateStars());
        annotationLabel.setText(book.getAnnotation() != null ? book.getAnnotation() : "");
        readingProgress.setProgress(book.getProgress() / 100.0);
        progressLabel.setText(book.getProgress() + "%");
    }

    private static String displayFormat(String fileName, String archiveEntry) {
        String source = archiveEntry != null && !archiveEntry.isBlank() ? archiveEntry : fileName;
        if (source == null || source.isBlank()) return "—";
        int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        int dot = source.lastIndexOf('.');
        return dot > slash && dot + 1 < source.length()
                ? source.substring(dot + 1).toUpperCase(java.util.Locale.ROOT)
                : "—";
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
        annotationLabel.setText("");
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
    private void onDownload() {
        if (currentBook == null) return;
        BookId id = BookId.fromString(currentBook.getId());
        javafx.stage.Window owner = titleLabel != null && titleLabel.getScene() != null ? titleLabel.getScene().getWindow() : null;
        bookDownloadCoordinator.downloadBatch(java.util.List.of(id), owner).whenComplete((result, error) -> {
            if (error == null && result != null && result.failed() == 0) UiExecutor.runOnUiThread(() -> setBookId(id));
        });
    }

    @FXML
    private void onRead() {
        if (currentBook != null) {
            navigationService.readBook(currentBook);
        }
    }

    @FXML
    private void onEdit() {
        if (currentBook == null) return;
        BookId id = BookId.fromString(currentBook.getId());
        javafx.stage.Window owner = titleLabel != null && titleLabel.getScene() != null ? titleLabel.getScene().getWindow() : null;
        if (classicLibraryActionsService.editBook(owner, id)) {
            setBookId(id);
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
            dialogService.showWarning("Немає груп", "Створіть групу перед додаванням книги.");
            return;
        }
        Optional<com.myhomelibcorp.application.dto.GroupDto> selected = dialogService.showChoiceDialog(
                groups,
                groups.get(0),
                "Додати до групи",
                "Виберіть групу для книги \"" + currentBook.getTitle() + "\"",
                "Група:"
        );
        selected.ifPresent(group -> {
            try {
                addBookToGroupUseCase.execute(group.getId(), currentBook.getId());
                dialogService.showInfo("Успішно", "Книгу додано до групи \"" + group.getName() + "\".");
                log.info("Книгу {} додано до групи {}", currentBook.getId(), group.getId());
            } catch (Exception e) {
                log.error("Помилка додавання книги до групи", e);
                dialogService.showError("Помилка", "Не вдалося додати книгу: " + e.getMessage());
            }
        });
    }

    @FXML
    private void onDeleteBook() {
        if (currentBook == null) return;
        boolean confirmed = dialogService.showConfirmation(
                "Видалення книги",
                "Видалити запис із каталогу?",
                "Файл на диску не видаляється. Книга: " + currentBook.getTitle());
        if (!confirmed) return;
        try {
            bookSaver.deleteBook(BookId.fromString(currentBook.getId()));
            dialogService.showInfo("Готово", "Книгу видалено з каталогу.");
            currentBook = null;
            navigateBackOrToAllBooks();
        } catch (Exception e) {
            log.error("Помилка видалення книги", e);
            dialogService.showError("Помилка", "Не вдалося видалити книгу: " + e.getMessage());
        }
    }


    @FXML
    private void onBack() {
        navigateBackOrToAllBooks();
    }

    private void navigateBackOrToAllBooks() {
        if (navigationService.canGoBack()) navigationService.goBack();
        else navigationService.navigateToAllBooks();
    }
}