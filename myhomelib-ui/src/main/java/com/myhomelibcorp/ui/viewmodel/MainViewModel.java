package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.book.LoadBooksByAuthorUseCase;
import com.myhomelibcorp.application.book.LoadBooksUseCase;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.genre.LoadGenresUseCase;
import com.myhomelibcorp.application.importing.ImportDirectoryUseCase;
import com.myhomelibcorp.application.importing.ImportFileUseCase;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.application.port.out.IndexRebuilder; // <-- НОВИЙ ІМПОРТ
import com.myhomelibcorp.application.search.SearchBooksUseCase;
import com.myhomelibcorp.domain.model.book.Book;
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
    private final SearchBooksUseCase searchBooksUseCase;
    private final ImportFileUseCase importFileUseCase;
    private final ImportDirectoryUseCase importDirectoryUseCase;
    private final LoadGenresUseCase loadGenresUseCase;
    private final GenreService genreService;
    private final BackgroundExecutor backgroundExecutor;
    private final IndexRebuilder indexRebuilder; // <-- НОВА ЗАЛЕЖНІСТЬ

    private final ObservableList<BookDto> books = FXCollections.observableArrayList();
    private final ObjectProperty<BookDto> selectedBook = new SimpleObjectProperty<>();
    private final StringProperty statusText = new SimpleStringProperty("Готово до роботи");
    private final DoubleProperty importProgress = new SimpleDoubleProperty(0);
    private final BooleanProperty importInProgress = new SimpleBooleanProperty(false);
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> genreNames = FXCollections.observableArrayList();

    private AuthorId currentAuthorId;
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));

    public ObservableList<BookDto> booksProperty() { return books; }
    public ObjectProperty<BookDto> selectedBookProperty() { return selectedBook; }
    public StringProperty statusTextProperty() { return statusText; }
    public DoubleProperty importProgressProperty() { return importProgress; }
    public BooleanProperty importInProgressProperty() { return importInProgress; }
    public StringProperty searchQueryProperty() { return searchQuery; }
    public ObservableList<String> genreNamesProperty() { return genreNames; }

    public void initWithoutBooks() {
        loadGenres();
        bindSearchWithDebounce();
    }

    public void refreshBooks() {
        statusText.set("Завантаження всіх книг...");
        backgroundExecutor.submit(() -> loadBooksUseCase.loadAll(10000, 0))
                .thenAccept(bookList -> {
                    List<BookDto> dtos = bookList.stream()
                            .sorted(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)))
                            .map(this::toDto)
                            .collect(Collectors.toList());
                    Platform.runLater(() -> {
                        books.setAll(dtos);
                        statusText.set("Завантажено " + dtos.size() + " книг");
                        if (!dtos.isEmpty()) selectedBook.set(dtos.get(0));
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка: " + ex.getMessage()));
                    log.error("Помилка завантаження книг", ex);
                    return null;
                });
    }

    public void searchBooks(String query) {
        log.debug("Пошук за запитом: '{}'", query);
        if (query == null || query.isBlank()) {
            if (currentAuthorId != null) {
                loadBooksByAuthor(currentAuthorId);
            } else {
                refreshBooks();
            }
            return;
        }

        statusText.set("Пошук: " + query);
        backgroundExecutor.submit(() -> {
                    log.debug("Виклик searchBooksUseCase.search('{}', 100)", query);
                    return searchBooksUseCase.search(query, 100);
                })
                .thenAccept(bookList -> {
                    log.debug("Отримано {} результатів", bookList.size());
                    List<BookDto> dtos = bookList.stream().map(this::toDto).collect(Collectors.toList());
                    Platform.runLater(() -> {
                        books.setAll(dtos);
                        statusText.set("Знайдено " + dtos.size() + " книг");
                        if (!dtos.isEmpty()) selectedBook.set(dtos.get(0));
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка пошуку: " + ex.getMessage()));
                    log.error("Помилка пошуку", ex);
                    return null;
                });
    }

    public void importFile(Path file, Runnable onComplete) {
        importInProgress.set(true);
        statusText.set("Імпорт файлу: " + file.getFileName());
        backgroundExecutor.submit(() -> importFileUseCase.importFile(file))
                .thenAccept(count -> Platform.runLater(() -> {
                    importInProgress.set(false);
                    statusText.set("Імпорт завершено. Додано " + count + " книг");
                    if (onComplete != null) onComplete.run();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        importInProgress.set(false);
                        statusText.set("Помилка імпорту: " + ex.getMessage());
                    });
                    log.error("Помилка імпорту файлу", ex);
                    return null;
                });
    }

    public void importDirectory(Path directory, Runnable onComplete) {
        importInProgress.set(true);
        statusText.set("Імпорт каталогу: " + directory.getFileName());
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        DoubleConsumer progressConsumer = progress -> Platform.runLater(() -> importProgress.set(progress));

        backgroundExecutor.submit(() -> importDirectoryUseCase.importDirectory(directory, progressConsumer, cancelFlag))
                .thenAccept(count -> Platform.runLater(() -> {
                    importInProgress.set(false);
                    importProgress.set(0);
                    statusText.set("Імпорт каталогу завершено. Додано " + count + " книг");
                    if (onComplete != null) onComplete.run();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        importInProgress.set(false);
                        importProgress.set(0);
                        statusText.set("Помилка імпорту каталогу: " + ex.getMessage());
                    });
                    log.error("Помилка імпорту каталогу", ex);
                    return null;
                });
    }

    public void loadBooksByAuthor(AuthorId authorId) {
        if (authorId == null) {
            refreshBooks();
            return;
        }
        currentAuthorId = authorId;
        statusText.set("Завантаження книг автора...");
        backgroundExecutor.submit(() -> loadBooksByAuthorUseCase.loadByAuthor(authorId, 10000, 0))
                .thenAccept(bookList -> {
                    List<BookDto> dtos = bookList.stream()
                            .sorted(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)))
                            .map(this::toDto)
                            .collect(Collectors.toList());
                    Platform.runLater(() -> {
                        books.setAll(dtos);
                        statusText.set("Книги автора: " + dtos.size() + " книг");
                        if (!dtos.isEmpty()) selectedBook.set(dtos.get(0));
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusText.set("Помилка: " + ex.getMessage()));
                    log.error("Помилка завантаження книг автора", ex);
                    return null;
                });
    }

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

    private BookDto toDto(Book book) {
        String genresText = book.getGenres().stream()
                .map(genre -> genreService.getGenreName(genre.getId().asString()))
                .collect(Collectors.joining(", "));

        return BookDto.builder()
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .series(book.getSeries())
                .genresText(genresText)
                .sequenceNumber(book.getSequenceNumber())
                .rate(book.getRate())
                .progress(book.getProgress())
                .language(book.getLanguage() != null ? book.getLanguage().toString() : "")
                .fileSize(book.getFileSize())
                .fileName(book.getFileName())
                .folder(book.getFolder())
                .updateDate(book.getUpdateDate())
                .annotation(book.getAnnotation())
                .build();
    }

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
}