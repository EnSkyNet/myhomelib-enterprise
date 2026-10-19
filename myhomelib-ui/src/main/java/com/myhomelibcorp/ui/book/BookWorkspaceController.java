package com.myhomelibcorp.ui.book;

import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.ui.service.BookDisplayText;
import com.myhomelibcorp.ui.details.BookDetailsAnalysisService;
import com.myhomelibcorp.ui.details.model.RichBookDetailsSession;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationType;
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
import java.util.concurrent.TimeUnit;
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
    private final BookDetailsAnalysisService detailsAnalysisService;

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
    private volatile UiBackgroundExecutor.CancellableSubmission<String> pendingAnnotation;
    private BookDto currentBook;

    @FXML
    public void initialize() {
        coverPresenter.bind(coverImageView);
    }

    public void setBookId(BookId bookId) {
        UiAsyncRequestGuard.invalidate(loadGeneration);
        cancelAnnotationLoad();
        Future<?> previous = pendingLoad;
        if (previous != null) previous.cancel(true);
        currentBook = null;

        if (bookId == null) {
            UiExecutor.runOnUiThread(() -> {
                clearUI();
                setLoadState(UiText.text("ui.java.book_workspace_controller.1215889a21"), true);
            });
            return;
        }

        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(loadGeneration, appState);
        UiExecutor.runOnUiThread(() -> setLoadState(UiText.text("ui.fxml.book_workspace.loading"), true));
        try {
            pendingLoad = backgroundExecutor.submitCollectionScopedCancellable(LibraryOperationType.QUERY, () -> {
                try {
                    Optional<BookDto> result = loadBookByIdUseCase.execute(bookId);
                    UiExecutor.runOnUiThread(() -> applyLoadedBook(bookId, requestToken, result));
                } catch (Throwable error) {
                    if (Thread.currentThread().isInterrupted()) return null;
                    UiExecutor.runOnUiThread(() -> applyLoadError(bookId, requestToken, error));
                }
                return null;
            });
        } catch (LibraryOperationConflictException | RejectedExecutionException rejectedOrBusy) {
            // Lifecycle conflicts and executor saturation are expected user-visible contention, not uncaught FX errors.
            applyLoadError(bookId, requestToken, rejectedOrBusy);
        }
    }

    private void applyLoadedBook(BookId bookId, UiAsyncRequestToken requestToken, Optional<BookDto> result) {
        if (!UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)) return;
        pendingLoad = null;
        if (result.isEmpty()) {
            log.warn("Book not found: {}", bookId);
            currentBook = null;
            clearUI();
            setLoadState(UiText.text("ui.java.book_workspace_controller.457c748374"), true);
            return;
        }
        // The sidebar can already hold a richer catalogue DTO for this very book.
        // Reuse its annotation before attempting a costly local FB2 inspection; never
        // borrow details from a previous selection or overwrite a nonempty DB value.
        BookDto loaded = result.get();
        var sidebar = appState.getBookDetails();
        currentBook = preferVisibleAnnotation(loaded, sidebar == null ? null : sidebar.getCurrentBook());
        updateUI(currentBook);
        coverPresenter.showCover(currentBook);
        setLoadState(null, false);
        loadMissingDocumentAnnotation(bookId, requestToken, currentBook);
    }

    /**
     * The catalogue may not contain an annotation although the local FB2 does.
     * Reuse the same document inspection as the right-hand Details panel, but only
     * for that missing field and never on the FX thread.
     */
    private void loadMissingDocumentAnnotation(BookId bookId, UiAsyncRequestToken requestToken, BookDto book) {
        if (book == null || (book.getAnnotation() != null && !book.getAnnotation().isBlank())) return;
        UiBackgroundExecutor.CancellableSubmission<String> task =
                backgroundExecutor.submitInteractiveCancellable(() -> {
                    // Details already probes the active collection root before treating a stale
                    // remote catalogue flag as authoritative. Match that behavior here too, on
                    // the worker thread: local-file reconciliation never performs an HTTP request.
                    boolean claimedLocal = book.isLocal() || book.getArtifacts().stream()
                            .anyMatch(artifact -> artifact != null && artifact.isLocal());
                    if (!claimedLocal && !bookDownloadCoordinator.reconcileLocalClaim(book)) {
                        return ""; // Genuine remote-only book: do not inspect or download.
                    }
                    try (RichBookDetailsSession session = detailsAnalysisService.analyze(book.getId())) {
                        return session.inspection().annotation();
                    }
                });
        pendingAnnotation = task;
        task.completion().orTimeout(5, TimeUnit.SECONDS)
                .thenAccept(annotation -> UiExecutor.runOnUiThread(() -> {
                    if (!UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)
                            || currentBook == null || !bookId.asString().equals(currentBook.getId())) return;
                    annotationLabel.setText(preferredAnnotation(currentBook.getAnnotation(), annotation));
                }))
                .exceptionally(failure -> {
                    task.cancel();
                    log.debug("Document annotation unavailable for {}: {}", bookId, failure.toString());
                    return null;
                })
                .whenComplete((ignored, failure) -> {
                    if (pendingAnnotation == task) pendingAnnotation = null;
                });
    }

    static BookDto preferVisibleAnnotation(BookDto loaded, BookDto visible) {
        if (loaded == null || visible == null || !java.util.Objects.equals(loaded.getId(), visible.getId())) {
            return loaded;
        }
        if (loaded.getAnnotation() != null && !loaded.getAnnotation().isBlank()) return loaded;
        if (visible.getAnnotation() == null || visible.getAnnotation().isBlank()) return loaded;
        return loaded.toBuilder().annotation(visible.getAnnotation()).build();
    }

    static String preferredAnnotation(String catalogAnnotation, String documentAnnotation) {
        if (catalogAnnotation != null && !catalogAnnotation.isBlank()) return catalogAnnotation;
        return documentAnnotation == null ? "" : documentAnnotation.strip();
    }

    private void cancelAnnotationLoad() {
        UiBackgroundExecutor.CancellableSubmission<String> task = pendingAnnotation;
        pendingAnnotation = null;
        if (task != null) task.cancel();
    }

    private void applyLoadError(BookId bookId, UiAsyncRequestToken requestToken, Throwable error) {
        if (!UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)) return;
        pendingLoad = null;
        currentBook = null;
        log.error("Failed to load book {}", bookId, error);
        clearUI();
        setLoadState(UiText.text("ui.java.book_workspace_controller.b1ab2d5954"), true);
    }

    private void setLoadState(String text, boolean visible) {
        if (loadStateLabel == null) return;
        loadStateLabel.setText(text == null ? "" : text);
        loadStateLabel.setVisible(visible);
        loadStateLabel.setManaged(visible);
    }

    private void updateUI(BookDto book) {
        titleLabel.setText(book.getTitle());
        String authors = book.getAuthorsText();
        authorLabel.setText(UiText.text("ui.java.book_workspace_controller.d8cfdbaf40")
                + (authors == null || authors.isBlank() ? UiText.text("common.author.unknown") : authors));
        seriesLabel.setText(UiText.text("ui.java.book_workspace_controller.629c14f6e5") + (book.getSeries() != null ? book.getSeries() : "—"));
        String localizedGenres = bookViewModelMapper.toViewModel(book).getGenresText();
        genresLabel.setText(UiText.text("ui.java.book_workspace_controller.bb25f108fa") + (localizedGenres == null || localizedGenres.isBlank() ? "—" : localizedGenres));
        languageLabel.setText(UiText.text("ui.java.book_workspace_controller.05fc3de293") + book.getLanguage());
        yearLabel.setText(UiText.text("ui.java.book_workspace_controller.71400a276c") + (book.getYear() != null && book.getYear() > 0 ? String.valueOf(book.getYear()) : "—"));
        publisherLabel.setText(UiText.text("ui.java.book_workspace_controller.1ee47fe5c7") + (book.getPublisher() != null ? book.getPublisher() : "—"));
        isbnLabel.setText(UiText.text("ui.common.isbn_prefix") + (book.getIsbn() != null ? book.getIsbn() : "—"));
        formatLabel.setText(UiText.text("ui.java.book_workspace_controller.e7c843a4f1") + displayFormat(book.getFileName(), book.getArchiveEntry()));
        sizeLabel.setText(UiText.text("ui.java.book_workspace_controller.173f73a83f") + BookDisplayText.fileSize(book.getFileSize()));
        ratingLabel.setText(UiText.text("ui.java.book_workspace_controller.f72f596b66") + book.getRateStars());
        annotationLabel.setText(preferredAnnotation(book.getAnnotation(), ""));
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
        titleLabel.setText(UiText.text("ui.bulkedit.field.title"));
        authorLabel.setText(UiText.text("ui.annotations.digest_author"));
        seriesLabel.setText(UiText.text("ui.bulkedit.field.series"));
        genresLabel.setText(UiText.text("ui.bulkedit.field.genres"));
        languageLabel.setText(UiText.text("ui.bulkedit.field.language"));
        yearLabel.setText(UiText.text("ui.bulkedit.field.year"));
        publisherLabel.setText(UiText.text("ui.bulkedit.field.publisher"));
        isbnLabel.setText(UiText.text("ui.common.isbn"));
        formatLabel.setText(UiText.text("ui.annotations.export_format"));
        sizeLabel.setText(UiText.text("ui.fxml.author_workspace.size"));
        ratingLabel.setText(UiText.text("ui.fxml.book_workspace.rating"));
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
        UiAsyncRequestToken refreshToken = UiAsyncRequestGuard.snapshot(loadGeneration, appState);
        javafx.stage.Window owner = titleLabel != null && titleLabel.getScene() != null ? titleLabel.getScene().getWindow() : null;
        bookDownloadCoordinator.downloadBatch(java.util.List.of(id), owner).whenComplete((result, error) -> {
            if (error == null && result != null && result.failed() == 0) {
                UiExecutor.runOnUiThread(() -> {
                    if (!UiAsyncRequestGuard.isCurrent(refreshToken, loadGeneration, appState)) return;
                    if (currentBook == null || !id.asString().equals(currentBook.getId())) return;
                    setBookId(id);
                });
            }
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
            dialogService.showWarning(UiText.text("ui.java.book_workspace_controller.5c79bfb614"), UiText.text("ui.java.book_workspace_controller.321ae2807b"));
            return;
        }
        String expectedCollectionId = currentCollectionId();
        var groups = loadGroupsUseCase.execute();
        if (groups.isEmpty()) {
            dialogService.showWarning(UiText.text("ui.java.book_workspace_controller.005249e3a0"), UiText.text("ui.java.book_workspace_controller.02a4c0cfab"));
            return;
        }
        Optional<com.myhomelibcorp.application.dto.GroupDto> selected = dialogService.showChoiceDialog(
                groups,
                groups.get(0),
                UiText.text("ui.fxml.book_workspace.add_to_group"),
                UiText.text("ui.java.book_workspace_controller.db070d47ad") + currentBook.getTitle() + "\"",
                UiText.text("ui.java.book_workspace_controller.483dbb9035"),
                com.myhomelibcorp.application.dto.GroupDto::getName
        );
        selected.ifPresent(group -> {
            try {
                addBookToGroupUseCase.execute(group.getId(), currentBook.getId(), expectedCollectionId);
                dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.book_workspace_controller.279310e5a2") + group.getName() + "\".");
                log.info("Книгу {} додано до групи {}", currentBook.getId(), group.getId());
            } catch (Exception e) {
                log.error("Помилка додавання книги до групи", e);
                dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.book_workspace_controller.c44c9a561c") + UiExceptionSupport.message(e));
            }
        });
    }

    @FXML
    private void onDeleteBook() {
        if (currentBook == null) return;
        String expectedCollectionId = currentCollectionId();
        boolean confirmed = dialogService.showConfirmation(
                UiText.text("ui.java.book_workspace_controller.295e234538"),
                UiText.text("ui.java.book_workspace_controller.08c1c4bbf8"),
                UiText.text("ui.java.book_workspace_controller.60adda3a47") + currentBook.getTitle());
        if (!confirmed) return;
        try {
            bookSaver.deleteBook(BookId.fromString(currentBook.getId()), expectedCollectionId);
            dialogService.showInfo(UiText.text("ui.java.book_workspace_controller.398c7d4f7b"), UiText.text("ui.java.book_workspace_controller.676b8a160f"));
            currentBook = null;
            navigateBackOrToAllBooks();
        } catch (Exception e) {
            log.error("Помилка видалення книги", e);
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.book_workspace_controller.35801b1742") + UiExceptionSupport.message(e));
        }
    }


    @FXML
    private void onBack() {
        navigateBackOrToAllBooks();
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null ? null : collection.getId();
    }

    private void navigateBackOrToAllBooks() {
        if (navigationService.canGoBack()) navigationService.goBack();
        else navigationService.navigateToAllBooks();
    }
    @Override
    public void dispose() {
        UiAsyncRequestGuard.invalidate(loadGeneration);
        cancelAnnotationLoad();
        Future<?> load = pendingLoad;
        if (load != null) load.cancel(true);
        pendingLoad = null;
        currentBook = null;
        coverPresenter.clearCover();
    }

}