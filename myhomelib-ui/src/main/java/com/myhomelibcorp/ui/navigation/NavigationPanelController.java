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
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationPanelController {

    private final LoadNavigationDataUseCase loadNavigationDataUseCase;
    private final DictionaryCachePort dictionaryCache;
    private final BookLoaderService bookLoaderService;
    private final AlphabetToolbarController alphabetToolbarController;

    private Consumer<AuthorId> onAuthorSelected;
    private Consumer<SeriesId> onSeriesSelected;
    private Consumer<GenreId> onGenreSelected;

    @FXML private ListView<Object> navigationListView;
    @FXML private TextField listSearchField;
    @FXML private Button authorsButton;
    @FXML private Button seriesButton;
    @FXML private Button genresButton;

    private List<Author> allAuthors;
    private List<Series> allSeries;
    private List<GenreDto> allGenres;

    public enum NavigationMode {
        AUTHORS, SERIES, GENRES
    }

    private NavigationMode currentMode = NavigationMode.AUTHORS;
    private char currentLetter = '*';
    private String currentQuery = "";

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
                if (item instanceof Author) {
                    setText(((Author) item).getFullName());
                } else if (item instanceof Series) {
                    setText(((Series) item).getName());
                } else if (item instanceof GenreDto) {
                    setText(((GenreDto) item).getName());
                }
            }
        });

        navigationListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) return;

            log.debug("Вибрано елемент: {}", selected);

            if (selected instanceof Author author) {
                if (onAuthorSelected != null) {
                    onAuthorSelected.accept(author.getId());
                }
            } else if (selected instanceof Series series) {
                if (onSeriesSelected != null) {
                    log.info("Навігація до серії: {} (id: {})", series.getName(), series.getId());
                    onSeriesSelected.accept(series.getId());
                }
            } else if (selected instanceof GenreDto genre) {
                if (onGenreSelected != null) {
                    onGenreSelected.accept(GenreId.fromCode(genre.getCode()));
                }
            }
        });

        listSearchField.textProperty().addListener((obs, old, query) -> {
            currentQuery = query;
            filterList();
        });

        alphabetToolbarController.setOnLetterSelected(letter -> {
            currentLetter = letter;
            filterList();
        });

        // Встановлюємо початковий стан кнопок
        setActiveButton(authorsButton);
        loadAuthors();
    }

    public void setNavigationCallbacks(
            Consumer<AuthorId> onAuthorSelected,
            Consumer<SeriesId> onSeriesSelected,
            Consumer<GenreId> onGenreSelected) {
        this.onAuthorSelected = onAuthorSelected;
        this.onSeriesSelected = onSeriesSelected;
        this.onGenreSelected = onGenreSelected;
    }

    public void refreshAll() {
        log.info("🔄 Оновлення навігаційної панелі");
        Platform.runLater(() -> {
            switch (currentMode) {
                case AUTHORS -> loadAuthors();
                case SERIES -> loadSeries();
                case GENRES -> loadGenres();
            }
        });
    }

    private void setActiveButton(Button activeButton) {
        String inactiveStyle = "-fx-background-color: transparent; -fx-text-fill: #333333; -fx-font-weight: normal;";
        authorsButton.setStyle(inactiveStyle);
        seriesButton.setStyle(inactiveStyle);
        genresButton.setStyle(inactiveStyle);

        String activeStyle = "-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;";
        activeButton.setStyle(activeStyle);
    }

    public void loadAuthors() {
        currentMode = NavigationMode.AUTHORS;
        setActiveButton(authorsButton);

        // ВИПРАВЛЕНО: сортуємо авторів за прізвищем (або повним ім'ям)
        List<Author> authors = dictionaryCache.getAllAuthors().stream()
                .sorted(Comparator.comparing(a -> {
                    String fullName = a.getFullName();
                    return fullName != null ? fullName.toLowerCase() : "";
                }))
                .collect(Collectors.toList());
        this.allAuthors = authors;
        filterList();
        log.info("📚 Завантажено та відсортовано {} авторів", authors.size());
    }

    public void loadSeries() {
        currentMode = NavigationMode.SERIES;
        setActiveButton(seriesButton);

        loadNavigationDataUseCase.execute().thenAccept(data -> {
            // ВИПРАВЛЕНО: сортуємо серії за назвою
            this.allSeries = data.getSeriesNames().stream()
                    .map(name -> new Series(SeriesId.generate(), name, null))
                    .sorted(Comparator.comparing(s -> {
                        String name = s.getName();
                        return name != null ? name.toLowerCase() : "";
                    }))
                    .collect(Collectors.toList());

            dictionaryCache.loadSeries(allSeries);

            filterList();
            log.info("📚 Завантажено та відсортовано {} серій", allSeries.size());
        }).exceptionally(ex -> {
            log.error("Помилка завантаження серій", ex);
            return null;
        });
    }

    public void loadGenres() {
        currentMode = NavigationMode.GENRES;
        setActiveButton(genresButton);

        loadNavigationDataUseCase.execute().thenAccept(data -> {
            // ВИПРАВЛЕНО: сортуємо жанри за назвою
            this.allGenres = data.getGenres().stream()
                    .sorted(Comparator.comparing(g -> {
                        String name = g.getName();
                        return name != null ? name.toLowerCase() : "";
                    }))
                    .collect(Collectors.toList());

            filterList();
            log.info("📚 Завантажено та відсортовано {} жанрів", allGenres.size());
        }).exceptionally(ex -> {
            log.error("Помилка завантаження жанрів", ex);
            return null;
        });
    }

    private void filterList() {
        char letter = currentLetter;
        String query = currentQuery.toLowerCase();

        UiExecutor.runOnUiThread(() -> {
            switch (currentMode) {
                case AUTHORS -> {
                    List<Author> filtered = allAuthors.stream()
                            .filter(a -> matchesFilter(a.getFullName(), letter, query))
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
        });
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

    public void selectLetter(char letter) {
        this.currentLetter = letter;
        alphabetToolbarController.selectLetter(letter);
        filterList();
    }

    public void clearSelection() {
        alphabetToolbarController.clearSelection();
        navigationListView.getSelectionModel().clearSelection();
    }

    @FXML
    public void onAuthors() {
        loadAuthors();
        clearSelection();
    }

    @FXML
    public void onSeries() {
        loadSeries();
        clearSelection();
    }

    @FXML
    public void onGenres() {
        loadGenres();
        clearSelection();
    }
}