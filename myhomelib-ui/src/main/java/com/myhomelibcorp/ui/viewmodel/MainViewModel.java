package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.book.LoadBooksByAuthorUseCase;
import com.myhomelibcorp.application.book.LoadBooksBySeriesUseCase;
import com.myhomelibcorp.application.book.LoadBooksUseCase;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.genre.LoadBooksByGenreUseCase;
import com.myhomelibcorp.application.genre.LoadGenresUseCase;
import com.myhomelibcorp.application.group.*;
import com.myhomelibcorp.application.importing.ImportDirectoryUseCase;
import com.myhomelibcorp.application.importing.ImportFileUseCase;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.application.port.out.IndexRebuilder;
import com.myhomelibcorp.application.search.SearchBooksUseCase;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainViewModel {

    private final LoadBooksUseCase loadBooksUseCase;
    private final LoadBooksByAuthorUseCase loadBooksByAuthorUseCase;
    private final LoadBooksBySeriesUseCase loadBooksBySeriesUseCase;
    private final LoadBooksByGenreUseCase loadBooksByGenreUseCase;
    private final SearchBooksUseCase searchBooksUseCase;
    private final ImportFileUseCase importFileUseCase;
    private final ImportDirectoryUseCase importDirectoryUseCase;
    private final LoadGenresUseCase loadGenresUseCase;
    private final GenreService genreService;
    private final BackgroundExecutor backgroundExecutor;
    private final IndexRebuilder indexRebuilder;

    private final LoadGroupsUseCase loadGroupsUseCase;
    private final LoadBooksByGroupUseCase loadBooksByGroupUseCase;
    private final CreateGroupUseCase createGroupUseCase;
    private final RenameGroupUseCase renameGroupUseCase;
    private final DeleteGroupUseCase deleteGroupUseCase;
    private final AddBookToGroupUseCase addBookToGroupUseCase;
    private final RemoveBookFromGroupUseCase removeBookFromGroupUseCase;

    private final ObservableList<BookDto> books = FXCollections.observableArrayList();
    private final ObjectProperty<BookDto> selectedBook = new SimpleObjectProperty<>();
    private final StringProperty statusText = new SimpleStringProperty("Готово до роботи");
    private final DoubleProperty importProgress = new SimpleDoubleProperty(0);
    private final BooleanProperty importInProgress = new SimpleBooleanProperty(false);
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> genreNames = FXCollections.observableArrayList();

    private String currentCollectionRoot = "";
    private AuthorId currentAuthorId;
    private String currentSeriesName;
    private String currentGenreCode;
    private Long currentGroupId;
    private boolean isSearchMode = false;

    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));

    // ==================== ГЕТЕРИ / СЕТЕРИ ====================

    public ObservableList<BookDto> booksProperty() { return books; }
    public ObjectProperty<BookDto> selectedBookProperty() { return selectedBook; }
    public StringProperty statusTextProperty() { return statusText; }
    public DoubleProperty importProgressProperty() { return importProgress; }
    public BooleanProperty importInProgressProperty() { return importInProgress; }
    public StringProperty searchQueryProperty() { return searchQuery; }
    public ObservableList<String> genreNamesProperty() { return genreNames; }

    public void setCurrentCollectionRoot(String collectionRoot) {
        this.currentCollectionRoot = collectionRoot != null ? collectionRoot : "";
        log.info("📁 CollectionRoot встановлено: {}", this.currentCollectionRoot);
        Platform.runLater(() -> {
            for (BookDto book : books) {
                book.setCollectionRoot(this.currentCollectionRoot);
            }
        });
    }

    public String getCurrentCollectionRoot() {
        return currentCollectionRoot;
    }

    // ==================== ЗБЕРЕЖЕННЯ КОНТЕКСТУ ====================

    private void saveCurrentContext() {
        if (currentAuthorId != null) {
            log.debug("Збережено контекст автора: {}", currentAuthorId);
        } else if (currentSeriesName != null && !currentSeriesName.isBlank()) {
            log.debug("Збережено контекст серії: {}", currentSeriesName);
        } else if (currentGenreCode != null && !currentGenreCode.isBlank()) {
            log.debug("Збережено контекст жанру: {}", currentGenreCode);
        } else if (currentGroupId != null) {
            log.debug("Збережено контекст групи: {}", currentGroupId);
        } else if (isSearchMode) {
            log.debug("Збережено контекст пошуку");
        } else {
            log.debug("Немає активного контексту, буде завантажено всі книги");
        }
    }

    public void restoreContextAndRefresh() {
        if (currentAuthorId != null) {
            loadBooksByAuthor(currentAuthorId);
        } else if (currentSeriesName != null && !currentSeriesName.isBlank()) {
            loadBooksBySeries(currentSeriesName);
        } else if (currentGenreCode != null && !currentGenreCode.isBlank()) {
            loadBooksByGenre(currentGenreCode);
        } else if (currentGroupId != null) {
            loadBooksByGroup(currentGroupId);
        } else if (isSearchMode && searchQuery.get() != null && !searchQuery.get().isBlank()) {
            searchBooks(searchQuery.get());
        } else {
            refreshBooks();
        }
    }

    // ==================== ІНІЦІАЛІЗАЦІЯ ====================

    public void initWithoutBooks() {
        loadGenres();                   // ВИПРАВЛЕНО: метод додано нижче
        bindSearchWithDebounce();       // ВИПРАВЛЕНО: метод додано нижче
    }

    // ==================== ЗАВАНТАЖЕННЯ КНИГ ====================

    public void refreshBooks() {
        currentAuthorId = null;
        currentSeriesName = null;
        currentGenreCode = null;
        currentGroupId = null;
        isSearchMode = false;

        statusText.set("Завантаження всіх книг...");
        log.info("🔄 refreshBooks() викликано");

        backgroundExecutor.submit(() -> {
                    try {
                        List<Book> bookList = loadBooksUseCase.loadAll(Integer.MAX_VALUE, 0);
                        log.info("📚 Отримано {} книг з БД", bookList != null ? bookList.size() : 0);
                        return bookList;
                    } catch (Exception e) {
                        log.error("❌ Помилка в loadAll", e);
                        throw new RuntimeException("Помилка завантаження книг", e);
                    }
                })
                .thenAccept(bookList -> {
                    if (bookList == null || bookList.isEmpty()) {
                        log.warn("⚠️ Список книг порожній");
                        Platform.runLater(() -> {
                            books.clear();
                            statusText.set("Книг не знайдено");
                        });
                        return;
                    }

                    List<BookDto> dtos = bookList.stream()
                            .sorted(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)))
                            .map(this::toDtoSafe)
                            .filter(dto -> dto != null)
                            .collect(Collectors.toList());

                    log.info("📋 Перетворено {} DTO", dtos.size());

                    Platform.runLater(() -> {
                        books.setAll(dtos);
                        statusText.set("Завантажено " + dtos.size() + " книг");
                        log.info("✅ Таблиця оновлена, книг у списку: {}", dtos.size());

                        if (!dtos.isEmpty()) {
                            selectedBook.set(dtos.get(0));
                            if (currentCollectionRoot.isEmpty()) {
                                detectAndSetRoot(dtos.get(0));
                            }
                        } else {
                            selectedBook.set(null);
                        }
                    });
                })
                .exceptionally(ex -> {
                    log.error("💥 Критична помилка в refreshBooks", ex);
                    Platform.runLater(() -> {
                        statusText.set("Помилка завантаження: " + ex.getMessage());
                        books.clear();
                    });
                    return null;
                });
    }

    // ==================== МЕТОДИ ЗАВАНТАЖЕННЯ З КОНТЕКСТОМ ====================

    public void loadBooksByAuthor(AuthorId authorId) {
        if (authorId == null) {
            refreshBooks();
            return;
        }
        currentAuthorId = authorId;
        currentSeriesName = null;
        currentGenreCode = null;
        currentGroupId = null;
        isSearchMode = false;

        statusText.set("Завантаження книг автора...");
        log.info("📖 Завантаження книг автора: {}", authorId);

        backgroundExecutor.submit(() -> loadBooksByAuthorUseCase.loadByAuthor(authorId, Integer.MAX_VALUE, 0))
                .thenAccept(bookList -> {
                    List<BookDto> dtos = bookList.stream()
                            .sorted(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)))
                            .map(this::toDtoSafe)
                            .filter(dto -> dto != null)
                            .collect(Collectors.toList());
                    Platform.runLater(() -> {
                        books.setAll(dtos);
                        statusText.set("Книги автора: " + dtos.size() + " книг");
                        if (!dtos.isEmpty()) {
                            selectedBook.set(dtos.get(0));
                            if (currentCollectionRoot.isEmpty()) {
                                detectAndSetRoot(dtos.get(0));
                            }
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка: " + ex.getMessage()));
                    log.error("Помилка завантаження книг автора", ex);
                    return null;
                });
    }

    public void loadBooksBySeries(String seriesName) {
        if (seriesName == null || seriesName.isBlank()) {
            refreshBooks();
            return;
        }
        currentAuthorId = null;
        currentSeriesName = seriesName;
        currentGenreCode = null;
        currentGroupId = null;
        isSearchMode = false;

        statusText.set("Завантаження книг серії: " + seriesName);
        log.info("📚 Завантаження серії: {}", seriesName);

        backgroundExecutor.submit(() -> loadBooksBySeriesUseCase.loadBySeries(seriesName, Integer.MAX_VALUE, 0))
                .thenAccept(bookList -> {
                    List<BookDto> dtos = bookList.stream()
                            .sorted(Comparator.comparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)))
                            .map(this::toDtoSafe)
                            .filter(dto -> dto != null)
                            .collect(Collectors.toList());
                    Platform.runLater(() -> {
                        books.setAll(dtos);
                        statusText.set("Книги серії: " + dtos.size() + " книг");
                        if (!dtos.isEmpty()) {
                            selectedBook.set(dtos.get(0));
                            if (currentCollectionRoot.isEmpty()) {
                                detectAndSetRoot(dtos.get(0));
                            }
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка: " + ex.getMessage()));
                    log.error("Помилка завантаження книг серії", ex);
                    return null;
                });
    }

    public void loadBooksByGenre(String genreCode) {
        if (genreCode == null || genreCode.isBlank()) {
            refreshBooks();
            return;
        }
        currentAuthorId = null;
        currentSeriesName = null;
        currentGenreCode = genreCode;
        currentGroupId = null;
        isSearchMode = false;

        statusText.set("Завантаження книг жанру...");
        log.info("🎭 Завантаження жанру: {}", genreCode);

        backgroundExecutor.submit(() -> loadBooksByGenreUseCase.loadByGenre(genreCode, Integer.MAX_VALUE, 0))
                .thenAccept(bookList -> {
                    List<BookDto> dtos = bookList.stream()
                            .sorted(Comparator.comparing(Book::getTitle))
                            .map(this::toDtoSafe)
                            .filter(dto -> dto != null)
                            .collect(Collectors.toList());
                    Platform.runLater(() -> {
                        books.setAll(dtos);
                        statusText.set("Книги жанру: " + dtos.size() + " книг");
                        if (!dtos.isEmpty()) {
                            selectedBook.set(dtos.get(0));
                            if (currentCollectionRoot.isEmpty()) {
                                detectAndSetRoot(dtos.get(0));
                            }
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка: " + ex.getMessage()));
                    log.error("Помилка завантаження книг жанру", ex);
                    return null;
                });
    }

    public void loadBooksByGroup(Long groupId) {
        if (groupId == null) {
            refreshBooks();
            return;
        }
        currentAuthorId = null;
        currentSeriesName = null;
        currentGenreCode = null;
        currentGroupId = groupId;
        isSearchMode = false;

        statusText.set("Завантаження книг групи...");
        log.info("👥 Завантаження групи: {}", groupId);

        backgroundExecutor.submit(() -> loadBooksByGroupUseCase.loadByGroup(groupId))
                .thenAccept(bookList -> {
                    List<BookDto> dtos = bookList.stream()
                            .sorted(Comparator.comparing(Book::getTitle))
                            .map(this::toDtoSafe)
                            .filter(dto -> dto != null)
                            .collect(Collectors.toList());
                    Platform.runLater(() -> {
                        books.setAll(dtos);
                        statusText.set("Книги групи: " + dtos.size() + " книг");
                        if (!dtos.isEmpty()) {
                            selectedBook.set(dtos.get(0));
                            if (currentCollectionRoot.isEmpty()) {
                                detectAndSetRoot(dtos.get(0));
                            }
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка: " + ex.getMessage()));
                    log.error("Помилка завантаження книг групи", ex);
                    return null;
                });
    }

    // ==================== ПОШУК ====================

    public void searchBooks(String query) {
        log.debug("🔍 Пошук за запитом: '{}'", query);
        if (query == null || query.isBlank()) {
            isSearchMode = false;
            if (currentAuthorId != null) {
                loadBooksByAuthor(currentAuthorId);
            } else if (currentSeriesName != null && !currentSeriesName.isBlank()) {
                loadBooksBySeries(currentSeriesName);
            } else if (currentGenreCode != null && !currentGenreCode.isBlank()) {
                loadBooksByGenre(currentGenreCode);
            } else if (currentGroupId != null) {
                loadBooksByGroup(currentGroupId);
            } else {
                refreshBooks();
            }
            return;
        }

        isSearchMode = true;
        currentAuthorId = null;
        currentSeriesName = null;
        currentGenreCode = null;
        currentGroupId = null;

        statusText.set("Пошук: " + query);
        log.info("🔍 Виконуємо пошук за запитом: {}", query);

        backgroundExecutor.submit(() -> searchBooksUseCase.search(query, 1000))
                .thenAccept(bookList -> {
                    List<BookDto> dtos = bookList.stream()
                            .map(this::toDtoSafe)
                            .filter(dto -> dto != null)
                            .collect(Collectors.toList());
                    Platform.runLater(() -> {
                        books.setAll(dtos);
                        statusText.set("Знайдено " + dtos.size() + " книг");
                        if (!dtos.isEmpty()) {
                            selectedBook.set(dtos.get(0));
                            if (currentCollectionRoot.isEmpty()) {
                                detectAndSetRoot(dtos.get(0));
                            }
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка пошуку: " + ex.getMessage()));
                    log.error("Помилка пошуку", ex);
                    return null;
                });
    }

    // ==================== ІМПОРТ ====================

    public void importFile(Path file, Runnable onComplete) {
        importInProgress.set(true);
        statusText.set("Імпорт файлу: " + file.getFileName());
        log.info("📥 Імпорт файлу: {}", file);

        backgroundExecutor.submit(() -> importFileUseCase.importFile(file))
                .thenAccept(count -> Platform.runLater(() -> {
                    importInProgress.set(false);
                    statusText.set("Імпорт завершено. Додано " + count + " книг");
                    log.info("✅ Імпорт файлу завершено, додано {} книг", count);
                    delayAndRefresh(onComplete);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        importInProgress.set(false);
                        statusText.set("Помилка імпорту: " + ex.getMessage());
                    });
                    log.error("❌ Помилка імпорту файлу", ex);
                    return null;
                });
    }

    public void importDirectory(Path directory, Runnable onComplete) {
        importInProgress.set(true);
        statusText.set("Імпорт каталогу: " + directory.getFileName());
        log.info("📥 Імпорт каталогу: {}", directory);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        DoubleConsumer progressConsumer = progress -> Platform.runLater(() -> importProgress.set(progress));

        backgroundExecutor.submit(() -> importDirectoryUseCase.importDirectory(directory, progressConsumer, cancelFlag))
                .thenAccept(count -> Platform.runLater(() -> {
                    importInProgress.set(false);
                    importProgress.set(0);
                    statusText.set("Імпорт каталогу завершено. Додано " + count + " книг");
                    log.info("✅ Імпорт каталогу завершено, додано {} книг", count);
                    delayAndRefresh(onComplete);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        importInProgress.set(false);
                        importProgress.set(0);
                        statusText.set("Помилка імпорту каталогу: " + ex.getMessage());
                    });
                    log.error("❌ Помилка імпорту каталогу", ex);
                    return null;
                });
    }

    // ==================== ОНОВЛЕННЯ ПІСЛЯ ІМПОРТУ ====================

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

    // ==================== ГРУПИ ====================

    public Group createGroup(String name) {
        return createGroupUseCase.execute(name);
    }

    public Group renameGroup(Long groupId, String newName) {
        return renameGroupUseCase.execute(groupId, newName);
    }

    public void deleteGroup(Long groupId) {
        deleteGroupUseCase.execute(groupId);
    }

    public void addBookToGroup(Long groupId, String bookId) {
        addBookToGroupUseCase.execute(groupId, bookId);
    }

    public void removeBookFromGroup(Long groupId, String bookId) {
        removeBookFromGroupUseCase.execute(groupId, bookId);
    }

    // ==================== ІНДЕКС ====================

    public void rebuildIndex() {
        statusText.set("Перебудова індексу...");
        backgroundExecutor.submit(() -> {
            indexRebuilder.rebuildIndex();
            Platform.runLater(() -> statusText.set("Індекс перебудовано"));
            return null;
        }).exceptionally(ex -> {
            Platform.runLater(() -> statusText.set("Помилка перебудови індексу: " + ex.getMessage()));
            log.error("Помилка перебудови індексу", ex);
            return null;
        });
    }

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

    private BookDto toDtoSafe(Book book) {
        try {
            if (book == null) return null;
            String genresText = "";
            try {
                genresText = book.getGenres().stream()
                        .map(genre -> genreService.getGenreName(genre.getId().asString()))
                        .collect(Collectors.joining(", "));
            } catch (Exception e) {
                log.warn("Помилка отримання назви жанру: {}", e.getMessage());
            }

            return BookDto.builder()
                    .id(book.getId() != null ? book.getId().asString() : "")
                    .title(book.getTitle() != null ? book.getTitle() : "")
                    .authorsText(book.authorsText() != null ? book.authorsText() : "")
                    .series(book.getSeries() != null ? book.getSeries() : "")
                    .genresText(genresText)
                    .sequenceNumber(book.getSequenceNumber())
                    .rate(book.getRate())
                    .progress(book.getProgress())
                    .language(book.getLanguage() != null ? book.getLanguage().toString() : "")
                    .fileSize(book.getFileSize())
                    .fileName(book.getFileName() != null ? book.getFileName() : "")
                    .folder(book.getFolder() != null ? book.getFolder() : "")
                    .archiveEntry(book.getArchiveEntry() != null ? book.getArchiveEntry() : "")
                    .updateDate(book.getUpdateDate())
                    .annotation(book.getAnnotation() != null ? book.getAnnotation() : "")
                    .deleted(book.isDeleted())
                    .local(book.isLocal())
                    .review(book.getReview() != null ? book.getReview() : "")
                    .createdAt(book.getCreatedAt())
                    .collectionRoot(currentCollectionRoot)
                    .build();
        } catch (Exception e) {
            log.error("Помилка перетворення книги {}: {}", book.getTitle(), e.getMessage(), e);
            return null;
        }
    }

    // ========== ДОДАНІ МЕТОДИ ==========
    private void bindSearchWithDebounce() {
        searchQuery.addListener((obs, old, query) -> {
            searchDebounce.stop();
            searchDebounce.setOnFinished(e -> {
                log.debug("Запуск пошуку після дебаунсу: '{}'", query);
                searchBooks(query);
            });
            searchDebounce.playFromStart();
        });
    }

    private void loadGenres() {
        backgroundExecutor.submit(() -> loadGenresUseCase.getAllGenreNames())
                .thenAccept(names -> Platform.runLater(() -> genreNames.setAll(names)))
                .exceptionally(ex -> {
                    log.error("Помилка завантаження жанрів", ex);
                    return null;
                });
    }
    // ==================================

    private void detectAndSetRoot(BookDto firstBook) {
        if (firstBook == null) return;
        String folder = firstBook.getFolder();
        if (folder == null || folder.isBlank()) return;
        try {
            Path folderPath = Paths.get(folder);
            Path rootPath;
            if (folderPath.isAbsolute()) {
                rootPath = folderPath.getParent() != null ? folderPath.getParent() : folderPath;
            } else {
                rootPath = Paths.get(System.getProperty("user.dir"));
            }
            setCurrentCollectionRoot(rootPath.toString());
            log.info("Автоматично визначено collectionRoot: {}", rootPath);
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