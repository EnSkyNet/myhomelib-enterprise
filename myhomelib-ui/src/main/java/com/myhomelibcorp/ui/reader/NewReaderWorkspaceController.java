package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.reader.ReaderSettingsState;
import com.myhomelibcorp.application.reader.ReaderSettingsStateService;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.application.service.ReadingHistoryService;
import com.myhomelibcorp.application.service.ReadingSessionService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.core.ReaderEngine.PreparedBook;
import com.myhomelibcorp.reader.render.javafx.ReaderView;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.MainLayoutService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class NewReaderWorkspaceController implements WorkspaceLifecycle {

    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final BookResourcePort bookResourcePort;
    private final BookMapper bookMapper;
    private final NavigationService navigationService;
    private final SessionService sessionService;
    private final ReadingHistoryService readingHistoryService;
    private final ReadingSessionService readingSessionService;
    private final DialogService dialogService;
    private final NewReaderPersistenceService persistenceService;
    private final ReaderSettingsStateService readerSettingsStateService;
    private final ApplicationContext springContext;
    private final UiBackgroundExecutor uiBackgroundExecutor;
    private final ApplicationState appState;
    private final MainLayoutService mainLayoutService;

    @FXML
    private StackPane readerContainer;

    private ReaderView readerView;
    private ProgressIndicator loadingIndicator;
    private BookDto currentBook;
    private BookId currentBookId;
    private Path materializedBookFile;
    private volatile boolean isDisposed = false;
    private final AtomicLong openGeneration = new AtomicLong();
    private volatile Future<?> openTask;

    private ReaderPositionAutosaver positionAutosaver;
    private boolean positionChanged = false;
    private boolean currentBookOverride = false;

    @FXML
    public void initialize() {
        log.info("📖 NewReaderWorkspaceController ініціалізовано");
        positionAutosaver = new ReaderPositionAutosaver(persistenceService);
        initializeReaderView();
    }

    private void initializeReaderView() {
        readerView = new ReaderView();
        readerView.setOnBackClick(this::onBack);
        readerView.setOnSettingsClick(this::showSettings);
        readerView.setOnSettingsChanged(this::persistReaderSettings);
        readerView.setOnBookmarkClick(this::addBookmark);
        readerView.setOnBookmarksClick(this::showBookmarks);
        readerView.setOnTocClick(this::showToc);
        readerView.setOnSearchClick(this::showSearch);
        readerView.setOnToggleLeftSidebarClick(mainLayoutService::toggleLeftSidebar);
        readerView.setOnToggleRightSidebarClick(mainLayoutService::toggleRightSidebar);
        readerView.getCanvas().setOnPositionChanged(pos -> {
            positionChanged = true;
            if (positionAutosaver != null) positionAutosaver.mark(pos);
        });

        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(64, 64);
        loadingIndicator.setVisible(false);
        loadingIndicator.setManaged(false);
        if (readerContainer != null) {
            readerContainer.getChildren().setAll(readerView, loadingIndicator);
        }
    }

    public void setBookId(BookId bookId) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setBookId(bookId));
            return;
        }
        if (bookId == null) {
            log.warn("❌ bookId is null");
            return;
        }
        if (isDisposed) {
            log.warn("❌ Controller вже знищено, пропускаємо");
            return;
        }

        long generation = openGeneration.incrementAndGet();
        cancelPendingOpen();
        closeCurrentBookForReplacement();
        setLoading(true);
        log.info("📖 Асинхронна підготовка книги: {}", bookId);

        try {
            ReaderEngine engine = readerView.getEngine();
            openTask = uiBackgroundExecutor.submitCancellable(() -> {
                try {
                    PreparedOpen prepared = prepareOpen(bookId, engine);
                    if (Thread.currentThread().isInterrupted()) {
                        prepared.closeAbandoned();
                        return null;
                    }
                    Platform.runLater(() -> applyPreparedOpen(generation, prepared));
                } catch (Throwable error) {
                    Platform.runLater(() -> handleOpenFailure(generation, bookId, error));
                }
                return null;
            });
        } catch (RejectedExecutionException e) {
            setLoading(false);
            dialogService.showError("Помилка", "Черга фонових операцій переповнена. Спробуйте ще раз.");
        }
    }

    /**
     * Показує прогрес завантаження книги.
     */
    private void showDownloadProgress(double progress, String status) {
        Platform.runLater(() -> {
            if (loadingIndicator != null) {
                loadingIndicator.setVisible(true);
                loadingIndicator.setManaged(true);
                if (progress > 0 && progress < 1) {
                    loadingIndicator.setProgress(progress);
                } else {
                    loadingIndicator.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                }
            }
            if (status != null && !status.isEmpty()) {
                appState.getStatusBar().setStatusText(status);
            }
        });
    }

    private PreparedOpen prepareOpen(BookId bookId, ReaderEngine engine) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Reader open cancelled");

        showDownloadProgress(-1, "📖 Завантаження метаданих книги...");

        BookDto dto = loadBookByIdUseCase.execute(bookId)
                .orElseThrow(() -> new IOException("Книгу не знайдено: " + bookId));
        Book book = bookMapper.toDomain(dto);

        showDownloadProgress(0.1, "📁 Пошук файлу книги...");
        Path filePath = bookResourcePort.locateBookFile(book)
                .orElseThrow(() -> new IOException("Файл книги не знайдено: " + book.getFileName()));

        showDownloadProgress(0.3, "📦 Підготовка файлу для читання...");
        MaterializedReaderSource materialized = materializeReaderEntryIfNeeded(book, filePath);
        PreparedBook preparedBook = null;
        try {
            ReaderSettingsState state = readerSettingsStateService.load(book.getId().asString());
            ReaderSettings settings = ReaderSettingsMapper.fromDomain(state.preferences());
            Optional<ReaderPosition> savedPosition = persistenceService.loadPosition(book.getId().asString());
            BookSource source = new FileBookSource(materialized.readerPath(), book.getId().asString());

            showDownloadProgress(0.6, "📄 Аналіз структури книги...");
            preparedBook = engine.prepare(source);

            showDownloadProgress(1.0, "✅ Готово до читання!");
            return new PreparedOpen(dto, book, preparedBook, materialized.temporaryPath(),
                    settings, state.bookOverride(), savedPosition);
        } catch (Throwable e) {
            if (preparedBook != null) preparedBook.close();
            deleteTemp(materialized.temporaryPath());
            throw e;
        }
    }

    private void applyPreparedOpen(long generation, PreparedOpen prepared) {
        if (isDisposed || generation != openGeneration.get()) {
            prepared.closeAbandoned();
            return;
        }
        try {
            currentBook = prepared.dto();
            currentBookId = prepared.book().getId();
            // Reader is also a book workspace. Keep the shared right details panel
            // bound to the currently opened book even if the panel was hidden while opening.
            appState.getBookDetails().setCurrentBook(currentBook);
            materializedBookFile = prepared.temporaryPath();
            currentBookOverride = prepared.bookOverride();
            positionChanged = false;

            if (loadingIndicator != null) {
                loadingIndicator.setVisible(false);
                loadingIndicator.setManaged(false);
            }

            readerView.applySettings(prepared.settings());
            readerView.openPrepared(prepared.preparedBook(), prepared.savedPosition().orElse(null));
            long totalTextLength = readerView.getEngine().getCurrentDocument() == null
                    ? 0L : readerView.getEngine().getCurrentDocument().totalTextLength();
            positionAutosaver.start(currentBookId.asString(), totalTextLength);
            readingSessionService.start(currentBookId.asString(), currentProgressPercent());
            setLoading(false);

            BookId openedId = currentBookId;
            uiBackgroundExecutor.execute(() -> {
                if (isDisposed || generation != openGeneration.get()) return;
                try {
                    sessionService.saveLastOpenedBookId(openedId.asString());
                } catch (RuntimeException error) {
                    log.warn("Не вдалося зберегти останню відкриту книгу: {}", rootMessage(error));
                }
                try {
                    readingHistoryService.recordOpened(openedId);
                } catch (RuntimeException error) {
                    // Reading history is auxiliary state and must never terminate the Reader background task.
                    log.warn("Не вдалося оновити історію читання: {}", rootMessage(error));
                }
            });
            log.info("✅ Книгу відкрито в Reader: {}", currentBook.getTitle());
        } catch (Throwable error) {
            prepared.preparedBook().close();
            deleteTemp(prepared.temporaryPath());
            materializedBookFile = null;
            setLoading(false);
            log.error("❌ Помилка підключення підготовленої книги", error);
            dialogService.showError("Помилка", "Не вдалося відкрити книгу: " + rootMessage(error));
        }
    }

    private void handleOpenFailure(long generation, BookId bookId, Throwable error) {
        if (generation != openGeneration.get() || isDisposed) return;
        setLoading(false);
        Throwable root = unwrap(error);
        if (root instanceof InterruptedException || root instanceof InterruptedIOException
                || root instanceof CancellationException) {
            log.debug("Reader open cancelled for {}", bookId);
            return;
        }
        log.error("❌ Помилка відкриття книги {}", bookId, root);
        dialogService.showError("Помилка", "Не вдалося відкрити книгу: " + rootMessage(root));
    }

    private void closeCurrentBookForReplacement() {
        if (currentBookId == null && (readerView == null || !readerView.isBookOpen())) return;
        savePosition();
        finishReadingSession();
        if (readerView != null && readerView.isBookOpen()) readerView.closeBook();
        cleanupMaterializedBookFile();
        currentBook = null;
        currentBookId = null;
        positionChanged = false;
    }

    private void cancelPendingOpen() {
        Future<?> task = openTask;
        openTask = null;
        if (task != null && !task.isDone()) task.cancel(true);
    }

    private void setLoading(boolean loading) {
        if (loadingIndicator != null) {
            loadingIndicator.setVisible(loading);
            loadingIndicator.setManaged(loading);
        }
        if (readerView != null) readerView.setDisable(loading);
    }

    private MaterializedReaderSource materializeReaderEntryIfNeeded(Book book, Path physicalPath) throws IOException {
        String archiveEntry = book.getArchiveEntry();
        boolean physicalArchive = bookResourcePort.isArchive(physicalPath.toString());
        if ((archiveEntry == null || archiveEntry.isBlank()) && !physicalArchive) {
            return new MaterializedReaderSource(physicalPath, null);
        }

        String selectedEntry = archiveEntry;
        if ((selectedEntry == null || selectedEntry.isBlank()) && physicalArchive) {
            // Keep the complete archive. ZipParser will preserve every supported book
            // and build a hierarchical book -> chapter TOC instead of silently opening
            // only the first member.
            return new MaterializedReaderSource(physicalPath, null);
        }
        if (selectedEntry == null || selectedEntry.isBlank() || !isReaderEntry(selectedEntry)) {
            return new MaterializedReaderSource(physicalPath, null);
        }

        Optional<InputStream> stream = physicalArchive
                ? bookResourcePort.readArchiveEntry(physicalPath, selectedEntry)
                : bookResourcePort.readBookData(book);
        if (stream.isEmpty()) throw new IOException("Не вдалося прочитати запис архіву: " + selectedEntry);

        Path temp = Files.createTempFile("myhomelib-reader-book-", readerSuffix(selectedEntry));
        boolean success = false;
        try (InputStream in = stream.get(); OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Reader materialization cancelled");
                if (read == 0) continue;
                total += read;
                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES)
                    throw new IOException("Книга в архіві перевищує безпечний ліміт Reader");
                out.write(buffer, 0, read);
            }
            success = true;
        } finally {
            if (!success) Files.deleteIfExists(temp);
        }
        return new MaterializedReaderSource(temp, temp);
    }

    private static void deleteTemp(Path temp) {
        if (temp == null) return;
        try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = unwrap(error);
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record MaterializedReaderSource(Path readerPath, Path temporaryPath) { }

    private record PreparedOpen(
            BookDto dto, Book book, PreparedBook preparedBook, Path temporaryPath,
            ReaderSettings settings, boolean bookOverride, Optional<ReaderPosition> savedPosition) {
        void closeAbandoned() {
            preparedBook.close();
            deleteTemp(temporaryPath);
        }
    }

    private boolean isReaderEntry(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".fb2") || lower.endsWith(".fbd") || lower.endsWith(".epub")
                || lower.endsWith(".txt") || lower.endsWith(".text") || lower.endsWith(".md");
    }

    private String readerSuffix(String name) {
        if (name == null) return ".book";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot) : ".book";
    }

    private void cleanupMaterializedBookFile() {
        Path temp = materializedBookFile;
        materializedBookFile = null;
        if (temp != null) {
            try { Files.deleteIfExists(temp); }
            catch (IOException e) { log.debug("Не вдалося видалити тимчасову книгу {}: {}", temp, e.getMessage()); }
        }
    }

    private void savePosition() {
        if (readerView == null || !readerView.isBookOpen() || currentBookId == null) {
            return;
        }
        try {
            ReaderPosition pos = readerView.getCurrentPosition();
            if (pos != null) {
                boolean saved;
                if (positionAutosaver != null) {
                    positionAutosaver.mark(pos);
                    saved = positionAutosaver.flush();
                } else {
                    saved = persistenceService.savePosition(currentBookId.asString(), pos, currentDocumentLength());
                }
                if (!saved) {
                    appState.getStatusBar().setStatusText("⚠ Позицію читання не вдалося зберегти; буде повторна спроба");
                }
            }
        } catch (Exception e) {
            log.warn("Не вдалося зберегти позицію: {}", e.getMessage());
        }
    }

    @FXML
    private void onBack() {
        if (isDisposed) {
            return;
        }

        if (positionChanged) {
            savePosition();
            positionChanged = false;
        } else {
            savePosition();
        }

        finishReadingSession();
        if (readerView != null && readerView.isBookOpen()) {
            readerView.closeBook();
        }
        cleanupMaterializedBookFile();

        navigationService.goBack();
    }

    private void finishReadingSession() {
        if (currentBookId == null) return;
        readingSessionService.finish(currentBookId.asString(), currentProgressPercent());
    }

    private long currentDocumentLength() {
        if (readerView == null || readerView.getEngine().getCurrentDocument() == null) return 0L;
        return Math.max(0L, readerView.getEngine().getCurrentDocument().totalTextLength());
    }

    private int currentProgressPercent() {
        if (readerView == null || !readerView.isBookOpen()) return 0;
        ReaderPosition position = readerView.getCurrentPosition();
        return position == null ? 0 : percentForPosition(position);
    }

    private int percentForPosition(ReaderPosition position) {
        if (position == null || readerView == null || readerView.getEngine().getCurrentDocument() == null) return 0;
        long total = currentDocumentLength();
        if (total <= 0) return 0;
        return Math.max(0, Math.min(100, (int) Math.round(position.getPercent(total))));
    }

    private void persistReaderSettings(ReaderSettings settings) {
        persistReaderSettings(settings, currentBookOverride);
    }

    private void persistReaderSettings(ReaderSettings settings, boolean perBook) {
        if (settings == null) return;
        String bookId = currentBookId != null ? currentBookId.asString() : null;
        if (perBook && bookId != null) {
            var previous = readerSettingsStateService.load(bookId).preferences();
            readerSettingsStateService.saveForBook(bookId, ReaderSettingsMapper.toDomain(settings, previous));
            currentBookOverride = true;
        } else {
            var previous = readerSettingsStateService.loadGlobal();
            readerSettingsStateService.saveGlobal(ReaderSettingsMapper.toDomain(settings, previous));
            if (bookId != null) readerSettingsStateService.clearBookOverride(bookId);
            currentBookOverride = false;
        }
    }

    private void showSettings(ReaderSettings settings) {
        if (isDisposed || readerView == null) {
            return;
        }
        javafx.stage.Window owner = readerContainer != null && readerContainer.getScene() != null
                ? readerContainer.getScene().getWindow()
                : null;

        ReaderSettingsDialog.show(owner, settings, currentBookOverride, readerView::applySettings)
                .ifPresent(result -> {
                    readerView.applySettings(result.settings());
                    persistReaderSettings(result.settings(), result.bookOverride());
                });
    }

    private void addBookmark() {
        if (isDisposed || readerView == null || !readerView.isBookOpen() || currentBookId == null) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog("Закладка");
        dialog.setTitle("Додати закладку");
        dialog.setHeaderText("Введіть назву закладки");
        dialog.setContentText("Назва:");

        Optional<String> result = dialog.showAndWait();
        String title = result.orElse("Закладка " + (persistenceService.getBookmarkCount(currentBookId.asString()) + 1));

        ReaderPosition pos = readerView.getCurrentPosition();
        if (pos != null) {
            long totalTextLength = readerView.getEngine().getCurrentDocument() == null
                    ? 0L : readerView.getEngine().getCurrentDocument().totalTextLength();
            Bookmark bookmark = persistenceService.saveBookmark(
                    currentBookId.asString(), pos, totalTextLength, title, "");
            if (bookmark != null) {
                dialogService.showInfo("Успішно", "Закладку додано: " + title);
                log.info("⭐ Закладку додано: {}", title);
            }
        }
    }

    private void showBookmarks() {
        if (isDisposed || readerView == null || !readerView.isBookOpen() || currentBookId == null) {
            return;
        }
        List<Bookmark> bookmarks = persistenceService.loadBookmarks(currentBookId.asString());
        if (bookmarks.isEmpty()) {
            dialogService.showInfo("Закладки", "У цієї книги ще немає закладок.");
            return;
        }

        List<BookmarkChoice> choices = bookmarks.stream().map(BookmarkChoice::new).toList();
        ChoiceDialog<BookmarkChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        dialog.setTitle("Закладки");
        dialog.setHeaderText("Виберіть закладку");
        dialog.setContentText("Закладка:");
        Optional<BookmarkChoice> selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return;
        }

        Bookmark bookmark = selected.get().bookmark();
        ButtonType goTo = new ButtonType("Перейти");
        ButtonType delete = new ButtonType("Видалити");
        Alert action = new Alert(Alert.AlertType.CONFIRMATION);
        action.setTitle("Закладка");
        action.setHeaderText(selected.get().toString());
        action.setContentText("Що зробити із закладкою?");
        action.getButtonTypes().setAll(goTo, delete, ButtonType.CANCEL);
        Optional<ButtonType> result = action.showAndWait();
        if (result.filter(goTo::equals).isPresent()) {
            long totalTextLength = readerView.getEngine().getCurrentDocument() == null
                    ? 0L : readerView.getEngine().getCurrentDocument().totalTextLength();
            readerView.goToPosition(persistenceService.bookmarkToPosition(bookmark, totalTextLength));
        } else if (result.filter(delete::equals).isPresent()) {
            persistenceService.deleteBookmark(bookmark.getId());
            dialogService.showInfo("Закладки", "Закладку видалено.");
        }
    }

    private record BookmarkChoice(Bookmark bookmark) {
        @Override
        public String toString() {
            String title = bookmark.getChapterTitle();
            if (title == null || title.isBlank()) title = "Закладка";
            return String.format(Locale.ROOT, "%s — %.1f%%", title, bookmark.getPosition());
        }
    }

    private void showToc() {
        if (isDisposed || readerView == null || !readerView.isBookOpen()) {
            return;
        }

        try {
            var document = readerView.getEngine().getCurrentDocument();
            if (document == null || document.toc() == null || document.toc().isEmpty()) {
                dialogService.showInfo("Зміст", "У книги немає розділів");
                return;
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/toc-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            TOCDialogController controller = loader.getController();
            controller.setEntries(document.toc().entries(), entry -> {
                ReaderPosition pos = new ReaderPosition(
                        Math.max(0, document.chapterIndexAt(entry.textOffset())),
                        entry.textOffset(),
                        0,
                        0
                );
                readerView.goToPosition(pos);
                positionChanged = true;
            });

            Stage stage = new Stage();
            stage.setTitle("Зміст");
            stage.setScene(new Scene(root, 400, 500));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(readerContainer.getScene().getWindow());
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття змісту", e);
            dialogService.showError("Помилка", "Не вдалося відкрити зміст: " + e.getMessage());
        }
    }

    private void showSearch() {
        if (isDisposed || readerView == null || !readerView.isBookOpen()) {
            return;
        }

        try {
            var document = readerView.getEngine().getCurrentDocument();
            if (document == null) {
                return;
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/search-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            SearchDialogController controller = loader.getController();
            controller.setDocument(document, pos -> {
                readerView.goToPosition(pos);
                positionChanged = true;
            });

            Stage stage = new Stage();
            stage.setTitle("Пошук");
            stage.setScene(new Scene(root, 500, 450));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(readerContainer.getScene().getWindow());
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття пошуку", e);
            dialogService.showError("Помилка", "Не вдалося відкрити пошук: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        if (isDisposed) {
            return;
        }
        isDisposed = true;
        openGeneration.incrementAndGet();
        cancelPendingOpen();

        log.info("🧹 NewReaderWorkspaceController: початок очищення");

        if (positionChanged) {
            savePosition();
            positionChanged = false;
        } else {
            savePosition();
        }

        if (positionAutosaver != null) {
            positionAutosaver.close();
            positionAutosaver = null;
        }

        finishReadingSession();
        if (readerView != null) {
            if (readerView.isBookOpen()) {
                readerView.closeBook();
            }
            readerView.dispose();
            readerView = null;
        }
        cleanupMaterializedBookFile();

        if (readerContainer != null) {
            Platform.runLater(() -> readerContainer.getChildren().clear());
        }

        persistenceService.clearCache();

        currentBook = null;
        currentBookId = null;

        log.info("🧹 NewReaderWorkspaceController знищено");
    }
}