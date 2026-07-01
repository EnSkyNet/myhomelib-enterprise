package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.application.port.out.SearchQueryService;
import com.myhomelibcorp.application.query.BookQuery;
import com.myhomelibcorp.application.query.Pagination;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableView;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchManager {

    private final SearchQueryService searchQueryService;
    private final BookQueryRepository bookQueryRepository;
    private final BackgroundExecutor backgroundExecutor;
    private final GenreService genreService;

    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
    private final AtomicReference<CompletableFuture<?>> currentSearch = new AtomicReference<>();

    public void bindLiveSearch(
            javafx.scene.control.TextField searchField,
            TableView<BookDto> tableView,
            Label statusLabel,
            ProgressIndicator progressIndicator
    ) {
        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            debounce.stop();
            debounce.setOnFinished(e -> executeSearch(newValue, tableView, statusLabel, progressIndicator));
            debounce.playFromStart();
        });
    }

    public void executeSearch(
            String query,
            TableView<BookDto> tableView,
            Label statusLabel,
            ProgressIndicator progressIndicator
    ) {
        CompletableFuture<?> old = currentSearch.get();
        if (old != null) old.cancel(true);

        Platform.runLater(() -> {
            progressIndicator.setVisible(true);
            statusLabel.setText("Пошук...");
        });

        CompletableFuture<Void> future = backgroundExecutor.submit(() -> {
            List<BookDto> result;
            if (query == null || query.isBlank()) {
                BookQuery bookQuery = BookQuery.builder()
                        .pagination(Pagination.of(100, 0))
                        .build();
                List<Book> books = bookQueryRepository.find(bookQuery);
                result = books.stream().map(this::toDto).collect(Collectors.toList());
            } else {
                List<String> bookIds = searchQueryService.searchBookIds(query, 100);
                if (bookIds.isEmpty()) {
                    result = List.of();
                } else {
                    List<BookId> ids = bookIds.stream().map(BookId::fromString).collect(Collectors.toList());
                    List<Book> books = bookQueryRepository.findByIds(ids);
                    result = books.stream().map(this::toDto).collect(Collectors.toList());
                }
            }
            return result;
        }).thenAccept(result -> Platform.runLater(() -> {
            ObservableList<BookDto> items = tableView.getItems();
            items.setAll(result);
            progressIndicator.setVisible(false);
            statusLabel.setText("Знайдено " + result.size() + " книг");
            if (!result.isEmpty()) tableView.getSelectionModel().selectFirst();
        })).exceptionally(ex -> {
            Platform.runLater(() -> {
                progressIndicator.setVisible(false);
                if (!(ex instanceof java.util.concurrent.CancellationException)) {
                    statusLabel.setText("Помилка пошуку: " + ex.getMessage());
                    log.error("Помилка пошуку", ex);
                }
            });
            return null;
        });

        currentSearch.set(future);
    }

    private BookDto toDto(Book book) {
        String genresText = book.getGenres().stream()
                .map(g -> genreService.getGenreName(g.getId().asString()))
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
                .updateDate(book.getUpdateDate())
                .annotation(book.getAnnotation())
                .build();
    }
}