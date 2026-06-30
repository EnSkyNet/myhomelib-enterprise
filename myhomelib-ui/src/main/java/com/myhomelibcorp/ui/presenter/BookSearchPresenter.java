package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.search.SearchBooksUseCase;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookSearchPresenter {

    private final SearchBooksUseCase searchBooksUseCase;
    private final BackgroundExecutor backgroundExecutor;
    private final StatusBarPresenter statusBarPresenter;

    private final StringProperty queryProperty = new SimpleStringProperty();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));

    private ObservableList<BookDto> bookList;
    private Runnable onSearchComplete;

    public void bind(ObservableList<BookDto> bookList, Runnable onSearchComplete) {
        this.bookList = bookList;
        this.onSearchComplete = onSearchComplete;
        queryProperty.addListener((obs, old, query) -> {
            debounce.stop();
            debounce.setOnFinished(e -> performSearch(query));
            debounce.playFromStart();
        });
    }

    public StringProperty queryProperty() {
        return queryProperty;
    }

    public void performSearch(String query) {
        statusBarPresenter.setStatus("Пошук...");
        backgroundExecutor.submit(() -> {
            if (query == null || query.isBlank()) {
                return List.<BookDto>of(); // або повернути всі книги, якщо потрібно
            }
            return searchBooksUseCase.execute(query, 1000);
        }).thenAccept(results -> UiExecutor.runOnUiThread(() -> {
            if (bookList != null) {
                bookList.setAll(results);
                statusBarPresenter.setStatus("Знайдено " + results.size() + " книг");
                if (onSearchComplete != null) onSearchComplete.run();
            }
        })).exceptionally(ex -> {
            UiExecutor.runOnUiThread(() ->
                    statusBarPresenter.setStatus("Помилка пошуку: " + ex.getMessage()));
            log.error("Search failed", ex);
            return null;
        });
    }
}