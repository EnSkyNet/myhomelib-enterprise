package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.search.SearchService;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;  // <-- ВИПРАВЛЕНО
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.SearchViewModel;
import javafx.animation.PauseTransition;
import javafx.beans.property.StringProperty;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookSearchPresenter {

    private final SearchService searchService;
    private final BackgroundExecutor backgroundExecutor;
    private final BookViewModelMapper viewModelMapper;
    private final ApplicationState appState;

    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));

    public StringProperty getQueryProperty() {
        return appState.getSearch().queryProperty();
    }

    public void bind() {
        SearchViewModel vm = appState.getSearch();
        vm.queryProperty().addListener((obs, old, query) -> {
            debounce.stop();
            debounce.setOnFinished(e -> performSearch(query));
            debounce.playFromStart();
        });
    }

    private void performSearch(String query) {
        SearchViewModel vm = appState.getSearch();
        vm.setSearching(true);
        vm.setStatusMessage("Пошук...");

        backgroundExecutor.submit(() -> {
            if (query == null || query.isBlank()) {
                return List.<BookDto>of();
            }
            return searchService.search(query, 1000);
        }).thenAccept(dtos -> UiExecutor.runOnUiThread(() -> {
            vm.setSearching(false);
            var vms = dtos.stream()
                    .map(viewModelMapper::toViewModel)
                    .collect(Collectors.toList());
            vm.setResults(vms);
            appState.getBookTable().setBooks(vms);
            vm.setStatusMessage("Знайдено " + vms.size() + " книг");
        })).exceptionally(ex -> {
            UiExecutor.runOnUiThread(() -> {
                vm.setSearching(false);
                vm.setStatusMessage("Помилка пошуку: " + ex.getMessage());
            });
            log.error("Search failed", ex);
            return null;
        });
    }
}