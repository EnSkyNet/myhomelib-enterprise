package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapperHelper;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.render.javafx.ReaderView;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class NewReaderWorkspaceController implements WorkspaceLifecycle {

    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final BookResourcePort bookResourcePort;
    private final BookMapperHelper bookMapperHelper;
    private final NavigationService navigationService;
    private final SessionService sessionService;
    private final DialogService dialogService;
    private final NewReaderPersistenceService persistenceService;
    private final ReaderPreferencesPort readerPreferencesPort;
    private final ApplicationContext springContext;

    @FXML
    private StackPane readerContainer;

    private ReaderView readerView;
    private BookDto currentBook;
    private BookId currentBookId;
    private Path materializedBookFile;
    private boolean isDisposed = false;

    // Таймер для автоматичного збереження позиції
    private AnimationTimer saveTimer;
    private long lastSaveTime = 0;
    private static final long SAVE_INTERVAL = 10_000_000_000L; // 10 секунд

    // Прапорець для перевірки чи була зміна позиції
    private boolean positionChanged = false;

    @FXML
    public void initialize() {
        log.info("📖 NewReaderWorkspaceController ініціалізовано");

        // Таймер для автоматичного збереження позиції
        saveTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (isDisposed || readerView == null || !readerView.isBookOpen() || currentBookId == null) {
                    return;
                }
                if (now - lastSaveTime > SAVE_INTERVAL) {
                    lastSaveTime = now;
                    // Зберігаємо позицію тільки якщо вона змінилася
                    if (positionChanged) {
                        savePosition();
                        positionChanged = false;
                    }
                }
            }
        };
        saveTimer.start();
    }

    public void setBookId(BookId bookId) {
        if (bookId == null) {
            log.warn("❌ bookId is null");
            return;
        }

        if (isDisposed) {
            log.warn("❌ Controller вже знищено, пропускаємо");
            return;
        }

        this.currentBookId = bookId;

        Optional<BookDto> bookOpt = loadBookByIdUseCase.execute(bookId);
        if (bookOpt.isEmpty()) {
            log.warn("❌ Книгу не знайдено: {}", bookId);
            return;
        }

        this.currentBook = bookOpt.get();
        log.info("📖 Відкриття книги: {}", currentBook.getTitle());

        sessionService.saveLastOpenedBookId(bookId.asString());

        try {
            openBook(currentBook);
        } catch (Exception e) {
            cleanupMaterializedBookFile();
            log.error("❌ Помилка відкриття книги", e);
            dialogService.showError("Помилка", "Не вдалося відкрити книгу: " + e.getMessage());
        }
    }

    private void openBook(BookDto bookDto) throws Exception {
        Book book = bookMapperHelper.toDomain(bookDto);

        cleanupMaterializedBookFile();
        Path filePath = bookResourcePort.locateBookFile(book)
                .orElseThrow(() -> new IOException("Файл книги не знайдено: " + book.getFileName()));

        Path readerPath = materializeReaderEntryIfNeeded(book, filePath);
        BookSource source = new FileBookSource(readerPath, book.getId().asString());

        if (readerView == null) {
            readerView = new ReaderView();

            // Формати FB2/FBD, EPUB, TXT/MD та legacy ZIP реєструються самим ReaderView.
            // Налаштовуємо колбеки
            readerView.setOnBackClick(this::onBack);
            readerView.setOnSettingsClick(this::showSettings);
            readerView.setOnBookmarkClick(this::addBookmark);
            readerView.setOnTocClick(this::showToc);
            readerView.setOnSearchClick(this::showSearch);

            // Додаємо до контейнера
            Platform.runLater(() -> {
                if (!isDisposed && readerContainer != null) {
                    readerContainer.getChildren().add(readerView);
                }
            });
        }

        // Завантажуємо збережені налаштування до першого layout книги.
        ReaderSettings savedSettings = ReaderSettingsMapper.fromDomain(readerPreferencesPort.loadPreferences());
        readerView.applySettings(savedSettings);

        // Відкриваємо книгу
        readerView.openBook(source);

        // Відновлюємо позицію з БД
        Optional<ReaderPosition> savedPosition = persistenceService.loadPosition(book.getId().asString());
        savedPosition.ifPresent(pos -> {
            log.info("📖 Відновлення позиції з БД: offset={}", pos.textOffset());
            Platform.runLater(() -> {
                if (!isDisposed && readerView != null && readerView.isBookOpen()) {
                    readerView.goToPosition(pos);
                }
            });
        });

        // Плануємо оновлення розмірів Canvas
        scheduleSizeUpdate();

        // Слухаємо зміну позиції через callback
        readerView.getCanvas().setOnPositionChanged(pos -> {
            positionChanged = true;
        });

        log.info("✅ Книгу відкрито в новому Reader: {}", book.getTitle());
    }

    /**
     * The catalog path of an archived book points to the physical container.
     * Reader formats, however, must receive the actual FB2/EPUB/TXT payload.
     * Materialize only one bounded entry and delete it when the workspace closes.
     */
    private Path materializeReaderEntryIfNeeded(Book book, Path physicalPath) throws IOException {
        String archiveEntry = book.getArchiveEntry();
        boolean physicalArchive = bookResourcePort.isArchive(physicalPath.toString());
        if ((archiveEntry == null || archiveEntry.isBlank()) && !physicalArchive) return physicalPath;

        String selectedEntry = archiveEntry;
        if ((selectedEntry == null || selectedEntry.isBlank()) && physicalArchive) {
            selectedEntry = bookResourcePort.listArchiveEntries(physicalPath).stream()
                    .filter(this::isReaderEntry)
                    .findFirst()
                    .orElse(null);
        }
        if (selectedEntry == null || selectedEntry.isBlank() || !isReaderEntry(selectedEntry)) {
            // Let Reader's ZIP fallback handle legacy FB2 ZIPs; unsupported containers
            // will produce the normal "unsupported format" error instead of guessing.
            return physicalPath;
        }

        Optional<InputStream> stream = physicalArchive
                ? bookResourcePort.readArchiveEntry(physicalPath, selectedEntry)
                : bookResourcePort.readBookData(book);
        if (stream.isEmpty()) throw new IOException("Не вдалося прочитати запис архіву: " + selectedEntry);

        String suffix = readerSuffix(selectedEntry);
        Path temp = Files.createTempFile("myhomelib-reader-book-", suffix);
        boolean success = false;
        try (InputStream in = stream.get(); OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
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
        materializedBookFile = temp;
        return temp;
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

    // Замість 4-х спроб, використовуємо одну з правильною перевіркою
    private void scheduleSizeUpdate() {
        // Чекаємо, поки Canvas отримає розміри через listener
        Platform.runLater(() -> {
            if (!isDisposed && readerView != null) {
                // Використовуємо updateSize з перевіркою
                readerView.getCanvas().updateSize();
                log.info("📐 Оновлення розмірів Canvas");
            }
        });
    }

    /**
     * Зберігає поточну позицію в БД.
     */
    private void savePosition() {
        if (readerView == null || !readerView.isBookOpen() || currentBookId == null) {
            return;
        }
        try {
            ReaderPosition pos = readerView.getCurrentPosition();
            if (pos != null) {
                persistenceService.savePosition(currentBookId.asString(), pos);
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

        // Зберігаємо позицію перед закриттям
        if (positionChanged) {
            savePosition();
            positionChanged = false;
        } else {
            savePosition();
        }

        if (readerView != null && readerView.isBookOpen()) {
            readerView.closeBook();
        }
        cleanupMaterializedBookFile();

        if (saveTimer != null) {
            saveTimer.stop();
        }

        navigationService.goBack();
    }

    private void showSettings(ReaderSettings settings) {
        if (isDisposed || readerView == null) {
            return;
        }
        javafx.stage.Window owner = readerContainer != null && readerContainer.getScene() != null
                ? readerContainer.getScene().getWindow()
                : null;

        ReaderSettingsDialog.show(owner, settings).ifPresent(updated -> {
            readerView.applySettings(updated);
            var previous = readerPreferencesPort.loadPreferences();
            readerPreferencesPort.savePreferences(ReaderSettingsMapper.toDomain(updated, previous));
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
            Bookmark bookmark = persistenceService.saveBookmark(
                    currentBookId.asString(),
                    pos,
                    title,
                    ""
            );
            if (bookmark != null) {
                dialogService.showInfo("Успішно", "Закладку додано: " + title);
                log.info("⭐ Закладку додано: {}", title);
            }
        }
    }

    private void showToc() {
        if (isDisposed || readerView == null || !readerView.isBookOpen()) {
            return;
        }

        try {
            var document = readerView.getEngine().getCurrentDocument();
            if (document == null || document.chapters().isEmpty()) {
                dialogService.showInfo("Зміст", "У книги немає розділів");
                return;
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/toc-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            TOCDialogController controller = loader.getController();
            controller.setChapters(document.chapters(), chapter -> {
                ReaderPosition pos = new ReaderPosition(
                        document.chapters().indexOf(chapter),
                        chapter.startOffset(),
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

        log.info("🧹 NewReaderWorkspaceController: початок очищення");

        // Зупиняємо таймер
        if (saveTimer != null) {
            saveTimer.stop();
            saveTimer = null;
        }

        // Зберігаємо позицію
        if (positionChanged) {
            savePosition();
            positionChanged = false;
        } else {
            savePosition();
        }

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

        // Очищуємо кеш позицій в persistence
        persistenceService.clearCache();

        currentBook = null;
        currentBookId = null;

        log.info("🧹 NewReaderWorkspaceController знищено");
    }
}