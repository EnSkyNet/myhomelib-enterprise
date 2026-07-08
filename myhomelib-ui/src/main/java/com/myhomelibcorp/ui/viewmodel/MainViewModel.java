package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.usecase.book.LoadBooksUseCase;
import com.myhomelibcorp.application.usecase.book.UpdateBookUseCase;
import com.myhomelibcorp.application.usecase.genre.LoadGenresUseCase;
import com.myhomelibcorp.application.usecase.group.*;
import com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.application.usecase.index.RebuildIndexUseCase;
import com.myhomelibcorp.application.usecase.search.SearchBooksUseCase;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.presenter.StatusBarPresenter;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainViewModel {

    private static final int DEFAULT_PAGE_SIZE = 10000;
    private static final int SEARCH_PAGE_SIZE = 1000;

    private final LoadBooksUseCase loadBooksUseCase;
    private final UpdateBookUseCase updateBookUseCase;
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
    private final BookViewModelMapper viewModelMapper;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final StatusBarPresenter statusBarPresenter;

    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();
    private final ObjectProperty<BookViewModel> selectedBook = new SimpleObjectProperty<>();
    private final StringProperty statusText = new SimpleStringProperty("Готово до роботи");
    private final DoubleProperty importProgress = new SimpleDoubleProperty(0);
    private final BooleanProperty importInProgress = new SimpleBooleanProperty(false);
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> genreNames = FXCollections.observableArrayList();

    private String currentCollectionRoot = "";
    private BookQuery currentQuery;
    private boolean isSearchMode = false;
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));

    // ====== ГЕТЕРИ ======
    public ObservableList<BookViewModel> booksProperty() { return books; }
    public ObjectProperty<BookViewModel> selectedBookProperty() { return selectedBook; }
    public StringProperty statusTextProperty() { return statusText; }
    public DoubleProperty importProgressProperty() { return importProgress; }
    public BooleanProperty importInProgressProperty() { return importInProgress; }
    public StringProperty searchQueryProperty() { return searchQuery; }
    public ObservableList<String> genreNamesProperty() { return genreNames; }

    // ====== СТАТИСТИКА КОЛЕКЦІЇ ======
    public void updateCollectionStats() {
        backgroundExecutor.submit(() -> {
                    long bookCount = loadBooksUseCase.count(BookQuery.builder().pagination(Pagination.of(1, 0)).build());
                    long authorCount = authorRepository.findAll().size();
                    long genreCount = genreRepository.getAllGenres().size();
                    return String.format("Книг: %d, Авторів: %d, Жанрів: %d", bookCount, authorCount, genreCount);
                }).thenAccept(stats -> Platform.runLater(() -> statusBarPresenter.setStatus(stats)))
                .exceptionally(ex -> {
                    log.error("Помилка отримання статистики", ex);
                    return null;
                });
    }

    // ====== ЗАВАНТАЖЕННЯ ======
    public void loadBooks(BookQuery query) {
        this.currentQuery = query;
        log.info("loadBooks: query={}, isSearchMode={}", query, isSearchMode);
        statusText.set("Завантаження...");
        backgroundExecutor.submit(() -> loadBooksUseCase.execute(query))
                .thenAccept(dtos -> Platform.runLater(() -> {
                    log.info("loadBooks: отримано {} книг", dtos.size());
                    List<BookViewModel> vms = dtos.stream()
                            .map(viewModelMapper::toViewModel)
                            .collect(Collectors.toList());
                    books.setAll(vms);
                    statusText.set("Завантажено " + vms.size() + " книг");
                    if (!vms.isEmpty()) {
                        selectedBook.set(vms.get(0));
                    } else {
                        selectedBook.set(null);
                    }
                    log.info("books.size() після оновлення: {}", books.size());
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка: " + ex.getMessage()));
                    log.error("Failed to load books", ex);
                    return null;
                });
    }

    public void loadBooksByAuthor(AuthorId authorId) {
        isSearchMode = false;
        BookQuery query = BookQuery.builder()
                .authorId(authorId)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .build();
        loadBooks(query);
    }

    public void loadBooksBySeries(String seriesName) {
        isSearchMode = false;
        BookQuery query = BookQuery.builder()
                .text(seriesName)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .build();
        loadBooks(query);
    }

    public void loadBooksByGenre(String genreCode) {
        isSearchMode = false;
        BookQuery query = BookQuery.builder()
                .genreId(GenreId.fromCode(genreCode))
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .build();
        loadBooks(query);
    }

    public void loadBooksByGroup(Long groupId) {
        isSearchMode = false;
        BookQuery query = BookQuery.builder()
                .groupId(GroupId.fromLong(groupId))
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
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
                .pagination(Pagination.of(SEARCH_PAGE_SIZE, 0))
                .build();
        loadBooks(query);
    }

    public void refreshBooks() {
        isSearchMode = false;
        currentQuery = null;
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
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

    public void loadAllBooks() {
        log.info("loadAllBooks() викликано");
        isSearchMode = false;
        currentQuery = null;
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .build();
        loadBooks(query);
    }

    // ====== ОНОВЛЕННЯ КНИГИ ======
    public void updateRating(BookDto book, int rating) {
        if (book == null || book.getId() == null) return;
        backgroundExecutor.submit(() -> {
            updateBookUseCase.updateRate(BookId.fromString(book.getId()), rating);
            return null;
        }).exceptionally(ex -> {
            Platform.runLater(() -> statusText.set("Помилка оновлення рейтингу: " + ex.getMessage()));
            log.error("Failed to update rating", ex);
            return null;
        });
    }

    public void updateProgress(BookDto book, int progress) {
        if (book == null || book.getId() == null) return;
        backgroundExecutor.submit(() -> {
            updateBookUseCase.updateProgress(BookId.fromString(book.getId()), progress);
            return null;
        }).exceptionally(ex -> {
            Platform.runLater(() -> statusText.set("Помилка оновлення прогресу: " + ex.getMessage()));
            log.error("Failed to update progress", ex);
            return null;
        });
    }

    // ====== ІНІЦІАЛІЗАЦІЯ ======
    public void initWithoutBooks() {
        loadGenres();
        bindSearchWithDebounce();
        updateCollectionStats(); // додано
    }

    // ====== ІМПОРТ ======
    public void importFile(Path file, Runnable onComplete) {
        importInProgress.set(true);
        statusText.set("Імпорт файлу: " + file.getFileName());
        backgroundExecutor.submit(() -> importFileUseCase.execute(file))
                .thenAccept(count -> Platform.runLater(() -> {
                    importInProgress.set(false);
                    statusText.set("Імпорт завершено. Додано " + count + " книг");
                    updateCollectionStats(); // додано
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
                    updateCollectionStats(); // додано
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
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> {
                if (onComplete != null) onComplete.run();
                restoreContextAndRefresh();
            });
        }).start();
    }

    // ====== ГРУПИ ======
    public Group createGroup(String name) { return createGroupUseCase.execute(name); }
    public Group renameGroup(Long groupId, String newName) { return renameGroupUseCase.execute(groupId, newName); }
    public void deleteGroup(Long groupId) { deleteGroupUseCase.execute(groupId); }
    public void addBookToGroup(Long groupId, String bookId) { addBookToGroupUseCase.execute(groupId, bookId); }
    public void removeBookFromGroup(Long groupId, String bookId) { removeBookFromGroupUseCase.execute(groupId, bookId); }

    // ====== ІНДЕКС ======
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

    // ====== ДОПОМІЖНІ ======
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

    public void setStatusText(String text) {
        statusText.set(text);
    }

    public void setSelectedBook(BookViewModel book) {
        this.selectedBook.set(book);
    }

    public BookViewModel getSelectedBook() {
        return selectedBook.get();
    }
}