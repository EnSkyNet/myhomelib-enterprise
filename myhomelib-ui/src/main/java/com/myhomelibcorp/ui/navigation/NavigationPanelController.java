package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.usecase.navigation.LoadNavigationDataUseCase;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.NavigationService;
import javafx.fxml.FXML;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationPanelController {

    private final LoadNavigationDataUseCase loadNavigationDataUseCase;
    private final DictionaryCachePort dictionaryCache;
    private final BookLoaderService bookLoaderService;
    private final AlphabetToolbarController alphabetToolbarController;
    private final NavigationService navigationService; // ДОДАНО

    @FXML private ListView<Object> navigationListView;
    @FXML private TextField listSearchField;

    private List<Author> allAuthors;
    private List<Series> allSeries;
    private List<GenreDto> allGenres;

    public enum NavigationMode {
        AUTHORS, SERIES, GENRES
    }

    private NavigationMode currentMode = NavigationMode.AUTHORS;

    @FXML
    public void initialize() {
        navigationListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                if (item instanceof Author) setText(((Author) item).getFullName());
                else if (item instanceof Series) setText(((Series) item).getName());
                else if (item instanceof GenreDto) setText(((GenreDto) item).getName());
            }
        });

        // ВИПРАВЛЕННЯ: Використовуємо navigationService замість eventPublisher
        navigationListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) return;
            if (selected instanceof Author author) {
                navigationService.navigateToAuthor(author.getId());
            } else if (selected instanceof Series series) {
                navigationService.navigateToSeries(series.getId());
            } else if (selected instanceof GenreDto genre) {
                navigationService.navigateToGenre(GenreId.fromCode(genre.getCode()));
            }
        });

        listSearchField.textProperty().addListener((obs, old, query) -> filterList(alphabetToolbarController.getSelectedLetter()));
        alphabetToolbarController.setOnLetterSelected(this::filterList);

        loadAuthors();
    }

    public void loadAuthors() {
        currentMode = NavigationMode.AUTHORS;
        List<Author> authors = dictionaryCache.getAllAuthors().stream().collect(Collectors.toList());
        this.allAuthors = authors;
        filterList('*');
    }

    public void loadSeries() {
        currentMode = NavigationMode.SERIES;
        loadNavigationDataUseCase.execute().thenAccept(data -> {
            this.allSeries = data.getSeriesNames().stream()
                    .map(name -> new Series(SeriesId.generate(), name, null))
                    .collect(Collectors.toList());
            filterList('*');
        });
    }

    public void loadGenres() {
        currentMode = NavigationMode.GENRES;
        loadNavigationDataUseCase.execute().thenAccept(data -> {
            this.allGenres = data.getGenres();
            filterList('*');
        });
    }

    private void filterList(char letter) {
        String query = listSearchField.getText().toLowerCase();

        switch (currentMode) {
            case AUTHORS -> {
                List<Author> filtered = allAuthors.stream()
                        .filter(a -> matchesFilter(a.getLastName(), letter, query))
                        .collect(Collectors.toList());
                navigationListView.getItems().setAll(filtered);
            }
            case SERIES -> {
                List<Series> filtered = allSeries.stream()
                        .filter(s -> matchesFilter(s.getName(), letter, query))
                        .collect(Collectors.toList());
                navigationListView.getItems().setAll(filtered);
            }
            case GENRES -> {
                List<GenreDto> filtered = allGenres.stream()
                        .filter(g -> matchesFilter(g.getName(), letter, query))
                        .collect(Collectors.toList());
                navigationListView.getItems().setAll(filtered);
            }
        }
    }

    private boolean matchesFilter(String name, char letter, String query) {
        if (name == null || name.isEmpty()) return false;
        if (!query.isEmpty() && !name.toLowerCase().contains(query)) return false;
        if (letter == '*') return true;
        if (letter == '#') {
            return !Character.isLetter(name.charAt(0));
        }
        return Character.toUpperCase(name.charAt(0)) == Character.toUpperCase(letter);
    }

    @FXML public void onAuthors() { loadAuthors(); }
    @FXML public void onSeries() { loadSeries(); }
    @FXML public void onGenres() { loadGenres(); }
}