package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableView;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class SearchManager {

    private final BookQueryRepository bookQueryRepository;
    private final BackgroundExecutor backgroundExecutor;

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
            debounce.setOnFinished(e -> executeSearch(
                    newValue,
                    tableView,
                    statusLabel,
                    progressIndicator
            ));
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
        if (old != null) {
            old.cancel(true);
        }

        Platform.runLater(() -> {
            progressIndicator.setVisible(true);
            statusLabel.setText("Пошук...");
        });

        CompletableFuture<Void> future = backgroundExecutor.submit(() -> {
            List<Book> books;
            if (query == null || query.isBlank()) {
                books = bookQueryRepository.findAll(100, 0);
            } else {
                books = bookQueryRepository.search(query, 100);
            }
            return books.stream()
                    .map(this::toDto)
                    .toList();
        }).thenAccept(result -> Platform.runLater(() -> {
            ObservableList<BookDto> items = tableView.getItems();
            items.setAll(result);
            progressIndicator.setVisible(false);
            statusLabel.setText("Знайдено " + result.size() + " книг");
            if (!result.isEmpty()) {
                tableView.getSelectionModel().selectFirst();
            }
        })).exceptionally(ex -> {
            Platform.runLater(() -> {
                progressIndicator.setVisible(false);
                if (!(ex instanceof java.util.concurrent.CancellationException)) {
                    statusLabel.setText("Помилка пошуку: " + ex.getMessage());
                }
            });
            return null;
        });

        currentSearch.set(future);
    }

    private BookDto toDto(Book book) {
        return BookDto.builder()
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .series(book.getSeries())
                .genresText(book.genresText())
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