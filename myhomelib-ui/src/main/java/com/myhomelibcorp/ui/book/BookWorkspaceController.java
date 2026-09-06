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
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class BookWorkspaceController implements WorkspaceLifecycle {

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
    private final UiBackgroundExecutor backgroundExecutor;
    private final ApplicationState appState;

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
    @FXML private Label loadStateLabel;

    private final AtomicLong loadGeneration = new AtomicLong();
    private volatile Future<?> pendingLoad;
    private BookDto currentBook;

    @FXML
    public void initialize() {
        coverPresenter.bind(coverImageView);
    }

    public void setBookId(BookId bookId) {
        UiAsyncRequestGuard.invalidate(loadGeneration);
        Future<?> previous = pendingLoad;
        if (previous != null) previous.cancel(true);
        currentBook = null;

        if (bookId == null) {
            UiExecutor.runOnUiThread(() -> {
                clearUI();
                setLoadState("Книгу не вибрано", true);
            });
            return;
        }

        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(loadGeneration, appState);
        UiExecutor.runOnUiThread(() -> setLoadState("Завантаження…", true));
        try {
            pendingLoad = backgroundExecutor.submitCancellable(() -> {
                try {
                    Optional<BookDto> result = loadBookByIdUseCase.execute(bookId);
                    UiExecutor.runOnUiThread(() -> applyLoadedBook(bookId, requestToken, result));
                } catch (Throwable error) {
                    if (Thread.currentThread().isInterrupted()) return null;
                    UiExecutor.runOnUiThread(() -> applyLoadError(bookId, requestToken, error));
                }
                return null;
            });
        } catch (RejectedExecutionException rejected) {
            applyLoadError(bookId, requestToken, rejected);
        }
    }

    private void applyLoadedBook(BookId bookId, UiAsyncRequestToken requestToken, Optional<BookDto> result) {
        if (!UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)) return;
        pendingLoad = null;
        if (result.isEmpty()) {
            log.warn("Book not found: {}", bookId);
            currentBook = null;
            clearUI();
            setLoadState("Книгу не знайдено", true);
            return;
        }
        currentBook = result.get();
        updateUI(currentBook);
        coverPresenter.showCover(bookViewModelMapper.toViewModel(currentBook));
        setLoadState(null, false);
    }

    private void applyLoadError(BookId bookId, UiAsyncRequestToken requestToken, Throwable error) {
        if (!UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)) return;
        pendingLoad = null;
        currentBook = null;
        log.error("Failed to load book {}", bookId, error);
        clearUI();
        setLoadState("Не вдалося завантажити книгу", true);
    }

    private void setLoadState(String text, boolean visible) {
        if (loadStateLabel == null) return;
        loadStateLabel.setText(text == null ? "" : text);
        loadStateLabel.setVisible(visible);
        loadStateLabel.setManaged(visible);
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
        classicLibraryActionsService.editBook(owner, id, () -> setBookId(id));
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
    @Override
    public void dispose() {
        UiAsyncRequestGuard.invalidate(loadGeneration);
        Future<?> load = pendingLoad;
        if (load != null) load.cancel(true);
        pendingLoad = null;
        currentBook = null;
        coverPresenter.clearCover();
    }

}