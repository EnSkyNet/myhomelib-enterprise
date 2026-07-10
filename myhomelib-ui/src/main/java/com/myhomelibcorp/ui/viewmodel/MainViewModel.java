package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.PageableBookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.query.book.PageableBookQuery;
import com.myhomelibcorp.application.query.common.PageRequest;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.application.usecase.book.LoadBooksUseCase;
import com.myhomelibcorp.application.usecase.book.UpdateBookUseCase;
import com.myhomelibcorp.application.usecase.group.*;
import com.myhomelibcorp.application.usecase.index.RebuildIndexUseCase;
import com.myhomelibcorp.application.usecase.search.SearchBooksUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.infrastructure.cache.DictionaryCache;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.presenter.StatusBarPresenter;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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

    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 1000;

    private final PageableBookQueryRepository pageableBookQueryRepository;
    private final BookQueryRepository bookQueryRepository;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;
    private final DictionaryCache dictionaryCache;
    private final BookViewModelMapper viewModelMapper;
    private final BackgroundExecutor backgroundExecutor;
    private final StatusBarPresenter statusBarPresenter;
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
    private final RebuildIndexUseCase rebuildIndexUseCase;

    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();
    private final ObjectProperty<BookViewModel> selectedBook = new SimpleObjectProperty<>();
    private final StringProperty statusText = new SimpleStringProperty("Готово до роботи");
    private final DoubleProperty importProgress = new SimpleDoubleProperty(0);
    private final BooleanProperty importInProgress = new SimpleBooleanProperty(false);
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> genreNames = FXCollections.observableArrayList();

    private PageResult<BookListItem> currentPageResult = PageResult.empty();
    private int currentPage = 0;
    private int pageSize = DEFAULT_PAGE_SIZE;
    private PageableBookQuery currentQuery;
    private boolean isSearchMode = false;
    private String currentSearchText = "";

    // Геттери
    public ObservableList<BookViewModel> booksProperty() { return books; }
    public ObjectProperty<BookViewModel> selectedBookProperty() { return selectedBook; }
    public StringProperty statusTextProperty() { return statusText; }
    public DoubleProperty importProgressProperty() { return importProgress; }
    public BooleanProperty importInProgressProperty() { return importInProgress; }
    public StringProperty searchQueryProperty() { return searchQuery; }
    public ObservableList<String> genreNamesProperty() { return genreNames; }

    // ========== ЗАВАНТАЖЕННЯ СТОРІНКИ ==========

    public void loadPage(PageableBookQuery query) {
        this.currentQuery = query;
        this.isSearchMode = query.text() != null && !query.text().isBlank();
        this.currentSearchText = isSearchMode ? query.text() : "";
        statusText.set("Завантаження...");
        backgroundExecutor.submit(() -> pageableBookQueryRepository.findPage(query))
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    this.currentPageResult = result;
                    List<BookViewModel> vms = result.content().stream()
                            .map(viewModelMapper::toViewModel)
                            .collect(Collectors.toList());
                    books.setAll(vms);
                    long total = result.totalElements();
                    int page = result.currentPage();
                    int totalPages = result.totalPages();
                    String msg = String.format("Показано %d з %d книг (сторінка %d/%d)",
                            vms.size(), total, page + 1, totalPages);
                    statusText.set(msg);
                    if (!vms.isEmpty()) {
                        selectedBook.set(vms.get(0));
                    } else {
                        selectedBook.set(null);
                    }
                    updateCollectionStats();
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> statusText.set("Помилка: " + ex.getMessage()));
                    log.error("Failed to load page", ex);
                    return null;
                });
    }

    public void nextPage() { /* ... */ }
    public void previousPage() { /* ... */ }
    public void setPageSize(int size) { /* ... */ }

    // ========== ЗРУЧНІ МЕТОДИ ЗАВАНТАЖЕННЯ ==========

    public void loadBooksByAuthor(AuthorId authorId) {
        currentPage = 0;
        PageableBookQuery query = PageableBookQuery.builder()
                .authorId(authorId)
                .pageRequest(new PageRequest(0, pageSize, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadPage(query);
    }

    public void loadBooksBySeries(String seriesName) {
        currentPage = 0;
        var seriesId = dictionaryCache.getSeriesId(seriesName).orElse(null);
        PageableBookQuery.Builder builder = PageableBookQuery.builder()
                .pageRequest(new PageRequest(0, pageSize, SortBy.TITLE, SortDirection.ASC));
        if (seriesId != null) {
            builder.seriesId(seriesId);
        } else {
            builder.text(seriesName);
        }
        loadPage(builder.build());
    }

    public void loadBooksByGenre(String genreCode) {
        currentPage = 0;
        PageableBookQuery query = PageableBookQuery.builder()
                .genreId(GenreId.fromCode(genreCode))
                .pageRequest(new PageRequest(0, pageSize, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadPage(query);
    }

    public void loadBooksByGroup(Long groupId) {
        currentPage = 0;
        PageableBookQuery query = PageableBookQuery.builder()
                .groupId(GroupId.fromLong(groupId))
                .pageRequest(new PageRequest(0, pageSize, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadPage(query);
    }

    public void loadAllBooks() {
        currentPage = 0;
        isSearchMode = false;
        currentSearchText = "";
        PageableBookQuery query = PageableBookQuery.builder()
                .pageRequest(new PageRequest(0, pageSize, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadPage(query);
    }

    public void searchBooks(String text) {
        if (text == null) text = "";
        currentPage = 0;
        isSearchMode = !text.isBlank();
        currentSearchText = text;
        PageableBookQuery query = PageableBookQuery.builder()
                .text(text)
                .pageRequest(new PageRequest(0, pageSize, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadPage(query);
    }

    /**
     * Оновлює список книг, використовуючи поточний контекст.
     * Якщо контекст відсутній – завантажує всі книги.
     */
    public void refreshBooks() {
        if (currentQuery != null) {
            loadPage(currentQuery);
        } else {
            loadAllBooks();
        }
    }

    public void restoreContextAndRefresh() {
        if (isSearchMode && !currentSearchText.isBlank()) {
            searchBooks(currentSearchText);
        } else if (currentQuery != null) {
            loadPage(currentQuery);
        } else {
            loadAllBooks();
        }
    }

    /**
     * Очищує всі кеші та стан перед перезавантаженням.
     */
    public void clearCaches() {
        dictionaryCache.clearAll();
        currentQuery = null;
        isSearchMode = false;
        currentSearchText = "";
        books.clear();
        // Скидаємо поточну сторінку
        currentPageResult = PageResult.empty();
        currentPage = 0;
        selectedBook.set(null);
        log.info("Кеші та стан очищено");
    }

    // ========== ОНОВЛЕННЯ КНИГИ ==========
    public void updateRating(BookId bookId, int rating) { /* ... */ }
    public void updateProgress(BookId bookId, int progress) { /* ... */ }

    // ========== ГРУПИ ==========
    public Group createGroup(String name) { return createGroupUseCase.execute(name); }
    public Group renameGroup(Long groupId, String newName) { return renameGroupUseCase.execute(groupId, newName); }
    public void deleteGroup(Long groupId) { deleteGroupUseCase.execute(groupId); }
    public void addBookToGroup(Long groupId, String bookId) { addBookToGroupUseCase.execute(groupId, bookId); }
    public void removeBookFromGroup(Long groupId, String bookId) { removeBookFromGroupUseCase.execute(groupId, bookId); }

    // ========== ІМПОРТ ==========
    public void importFile(Path file, Runnable onComplete) { /* ... */ }
    public void importDirectory(Path directory, Runnable onComplete) { /* ... */ }

    // ========== ІНДЕКС ==========
    public void rebuildIndex() { /* ... */ }

    // ========== СТАТИСТИКА ==========
    public void updateCollectionStats() { /* ... */ }

    // ========== ІНІЦІАЛІЗАЦІЯ ==========
    public void initWithoutBooks() {
        loadGenres();
        updateCollectionStats();
        loadAllBooks();
    }

    private void loadGenres() {
        backgroundExecutor.submit(() -> genreRepository.getAllGenreNames())
                .thenAccept(names -> UiExecutor.runOnUiThread(() -> genreNames.setAll(names)))
                .exceptionally(ex -> {
                    log.error("Failed to load genres", ex);
                    return null;
                });
    }

    // ========== ДОДАТКОВІ МЕТОДИ ==========


    public void refreshDictionaries() {
        backgroundExecutor.submit(() -> {
            dictionaryCache.loadAuthors(authorRepository.findAll());
            dictionaryCache.loadGenres(genreRepository.findAll());
            dictionaryCache.loadSeries(seriesRepository.findAll());
            log.info("Словники оновлено після імпорту");
            return null;
        }).exceptionally(ex -> {
            log.error("Failed to refresh dictionaries", ex);
            return null;
        });
    }

    public void setStatusText(String text) { statusText.set(text); }
    public void setSelectedBook(BookViewModel book) { selectedBook.set(book); }
    public BookViewModel getSelectedBook() { return selectedBook.get(); }
    public int getCurrentPage() { return currentPage; }
    public int getPageSize() { return pageSize; }
    public long getTotalElements() { return currentPageResult.totalElements(); }
    public int getTotalPages() { return currentPageResult.totalPages(); }
    public boolean hasNextPage() { return currentPageResult.hasNext(); }
    public boolean hasPreviousPage() { return currentPageResult.hasPrevious(); }
    public String getCurrentSearchText() { return currentSearchText; }
    public boolean isSearchMode() { return isSearchMode; }
    public void clearSearch() {
        searchQuery.set("");
        currentSearchText = "";
        isSearchMode = false;
        loadAllBooks();
    }


}