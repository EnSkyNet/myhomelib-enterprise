package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.query.BookQuery;
import com.myhomelibcorp.application.query.Pagination;
import com.myhomelibcorp.application.usecase.book.LoadBooksUseCase;
import com.myhomelibcorp.application.usecase.genre.LoadGenresUseCase;
import com.myhomelibcorp.application.usecase.group.*;
import com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.application.usecase.index.RebuildIndexUseCase;
import com.myhomelibcorp.application.usecase.search.SearchBooksUseCase;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainViewModel {

    private final LoadBooksUseCase loadBooksUseCase;
    private final CreateGroupUseCase createGroupUseCase;
    private final RenameGroupUseCase renameGroupUseCase;
    private final DeleteGroupUseCase deleteGroupUseCase;
    private final AddBookToGroupUseCase addBookToGroupUseCase;
    private final RemoveBookFromGroupUseCase removeBookFromGroupUseCase;
    private final SearchBooksUseCase searchBooksUseCase;
    private final ImportFileUseCase importFileUseCase;
    private final ImportDirectoryUseCase importDirectoryUseCase;
    private final LoadGenresUseCase loadGenresUseCase;
    private final RebuildIndexUseCase rebuildIndexUseCase;
    private final BackgroundExecutor backgroundExecutor;

    private final ObservableList<BookDto> books = FXCollections.observableArrayList();
    private final ObjectProperty<BookDto> selectedBook = new SimpleObjectProperty<>();
    private final StringProperty statusText = new SimpleStringProperty("Готово до роботи");
    private final DoubleProperty importProgress = new SimpleDoubleProperty(0);
    private final BooleanProperty importInProgress = new SimpleBooleanProperty(false);
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> genreNames = FXCollections.observableArrayList();

    private String currentCollectionRoot = "";
    private BookQuery currentQuery;
    private boolean isSearchMode = false;
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));

    // === ГЕТЕРИ / СЕТЕРИ ===
    public ObservableList<BookDto> booksProperty() { return books; }
    public ObjectProperty<BookDto> selectedBookProperty() { return selectedBook; }
    public StringProperty statusTextProperty() { return statusText; }
    public DoubleProperty importProgressProperty() { return importProgress; }
    public BooleanProperty importInProgressProperty() { return importInProgress; }
    public StringProperty searchQueryProperty() { return searchQuery; }
    public ObservableList<String> genreNamesProperty() { return genreNames; }

    public void setCurrentCollectionRoot(String collectionRoot) {
        this.currentCollectionRoot = collectionRoot != null ? collectionRoot : "";
        log.info("CollectionRoot встановлено: {}", this.currentCollectionRoot);
        Platform.runLater(() -> {
            for (BookDto book : books) {
                book.setCollectionRoot(this.currentCollectionRoot);
            }
        });
    }

    public String getCurrentCollectionRoot() {
        return currentCollectionRoot;
    }

    // === ЗАВАНТАЖЕННЯ КНИГ (з використанням BookQuery) ===

    public void loadBooks(BookQuery query) {
        this.currentQuery = query;
        statusText.set("Завантаження...");
        backgroundExecutor.submit(() -> loadBooksUseCase.execute(query))
                .thenAccept(dtos -> Platform.runLater(() -> {
                    books.setAll(dtos);
                    statusText.set("Завантажено " + dtos.size() + " книг");
                    if (!dtos.isEmpty()) {
                        selectedBook.set(dtos.get(0));
                        if (currentCollectionRoot.isEmpty()) {
                            detectAndSetRoot(dtos.get(0));
                        }
                    } else {
                        selectedBook.set(null);
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка: " + ex.getMessage()));
                    log.error("Failed to load books", ex);
                    return null;
                });
    }

    // === МЕТОДИ-ОБГОРТКИ (для зручності виклику з UI) ===

    public void loadBooksByAuthor(AuthorId authorId) {
        isSearchMode = false;
        BookQuery query = BookQuery.builder()
                .authorId(authorId)
                .pagination(Pagination.of(Integer.MAX_VALUE, 0))
                .build();
        loadBooks(query);
    }

    public void loadBooksBySeries(String seriesName) {
        // Поки що шукаємо за текстом, але в майбутньому замінимо на SeriesId
        isSearchMode = false;
        BookQuery query = BookQuery.builder()
                .text(seriesName)
                .pagination(Pagination.of(Integer.MAX_VALUE, 0))
                .build();
        loadBooks(query);
    }

    public void loadBooksByGenre(String genreCode) {
        isSearchMode = false;
        BookQuery query = BookQuery.builder()
                .genreId(GenreId.fromCode(genreCode))
                .pagination(Pagination.of(Integer.MAX_VALUE, 0))
                .build();
        loadBooks(query);
    }

    public void loadBooksByGroup(Long groupId) {
        isSearchMode = false;
        BookQuery query = BookQuery.builder()
                .groupId(GroupId.fromLong(groupId))
                .pagination(Pagination.of(Integer.MAX_VALUE, 0))
                .build();
        loadBooks(query);
    }

    public void searchBooks(String text) {
        if (text == null || text.isBlank()) {
            restoreContextAndRefresh();
            return;
        }
        isSearchMode = true;
        BookQuery query = BookQuery.builder()
                .text(text)
                .pagination(Pagination.of(1000, 0))
                .build();
        loadBooks(query);
    }

    public void refreshBooks() {
        isSearchMode = false;
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(Integer.MAX_VALUE, 0))
                .build();
        loadBooks(query);
    }

    public void restoreContextAndRefresh() {
        if (currentQuery != null) {
            loadBooks(currentQuery);
        } else {
            refreshBooks();
        }
    }

    // === ІНІЦІАЛІЗАЦІЯ ===
    public void initWithoutBooks() {
        loadGenres();
        bindSearchWithDebounce();
    }

    // === ІМПОРТ ===
    public void importFile(Path file, Runnable onComplete) {
        importInProgress.set(true);
        statusText.set("Імпорт файлу: " + file.getFileName());
        backgroundExecutor.submit(() -> importFileUseCase.execute(file))
                .thenAccept(count -> Platform.runLater(() -> {
                    importInProgress.set(false);
                    statusText.set("Імпорт завершено. Додано " + count + " книг");
                    delayAndRefresh(onComplete);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        importInProgress.set(false);
                        statusText.set("Помилка імпорту: " + ex.getMessage());
                    });
                    log.error("File import failed", ex);
                    return null;
                });
    }

    public void importDirectory(Path directory, Runnable onComplete) {
        importInProgress.set(true);
        statusText.set("Імпорт каталогу: " + directory.getFileName());
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        DoubleConsumer progressConsumer = progress -> Platform.runLater(() -> importProgress.set(progress));
        backgroundExecutor.submit(() -> importDirectoryUseCase.execute(directory, progressConsumer, cancelFlag))
                .thenAccept(count -> Platform.runLater(() -> {
                    importInProgress.set(false);
                    importProgress.set(0);
                    statusText.set("Імпорт завершено. Додано " + count + " книг");
                    delayAndRefresh(onComplete);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        importInProgress.set(false);
                        importProgress.set(0);
                        statusText.set("Помилка імпорту каталогу: " + ex.getMessage());
                    });
                    log.error("Directory import failed", ex);
                    return null;
                });
    }

    private void delayAndRefresh(Runnable onComplete) {
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            Platform.runLater(() -> {
                if (onComplete != null) onComplete.run();
                restoreContextAndRefresh();
            });
        }).start();
    }

    // === ГРУПИ ===
    public Group createGroup(String name) { return createGroupUseCase.execute(name); }
    public Group renameGroup(Long groupId, String newName) { return renameGroupUseCase.execute(groupId, newName); }
    public void deleteGroup(Long groupId) { deleteGroupUseCase.execute(groupId); }
    public void addBookToGroup(Long groupId, String bookId) { addBookToGroupUseCase.execute(groupId, bookId); }
    public void removeBookFromGroup(Long groupId, String bookId) { removeBookFromGroupUseCase.execute(groupId, bookId); }

    // === ІНДЕКС ===
    public void rebuildIndex() {
        statusText.set("Перебудова індексу...");
        backgroundExecutor.submit(() -> {
            rebuildIndexUseCase.execute();
            Platform.runLater(() -> statusText.set("Індекс перебудовано"));
            return null;
        }).exceptionally(ex -> {
            Platform.runLater(() -> statusText.set("Помилка перебудови індексу: " + ex.getMessage()));
            log.error("Index rebuild failed", ex);
            return null;
        });
    }

    // === ДОПОМІЖНІ ===
    private void bindSearchWithDebounce() {
        searchQuery.addListener((obs, old, query) -> {
            searchDebounce.stop();
            searchDebounce.setOnFinished(e -> searchBooks(query));
            searchDebounce.playFromStart();
        });
    }

    private void loadGenres() {
        backgroundExecutor.submit(() -> loadGenresUseCase.getAllGenreNames())
                .thenAccept(names -> Platform.runLater(() -> genreNames.setAll(names)))
                .exceptionally(ex -> {
                    log.error("Failed to load genres", ex);
                    return null;
                });
    }

    private void detectAndSetRoot(BookDto firstBook) {
        if (firstBook == null) return;
        String folder = firstBook.getFolder();
        if (folder == null || folder.isBlank()) return;
        try {
            Path folderPath = Paths.get(folder);
            if (folderPath.isAbsolute()) {
                setCurrentCollectionRoot("");
                return;
            }
            Path rootPath = folderPath.getParent() != null ? folderPath.getParent() : folderPath;
            setCurrentCollectionRoot(rootPath.toString());
        } catch (Exception e) {
            log.warn("Не вдалося визначити collectionRoot", e);
        }
    }

    public void setStatusText(String text) {
        statusText.set(text);
    }

    public void setSelectedBook(BookDto book) {
        this.selectedBook.set(book);
    }

    public BookDto getSelectedBook() {
        return selectedBook.get();
    }
}