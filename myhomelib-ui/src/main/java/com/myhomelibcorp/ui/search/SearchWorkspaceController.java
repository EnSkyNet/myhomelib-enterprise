package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.search.SearchService;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.mapper.GenreMapper;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchWorkspaceController {

    private final SearchService searchService;
    private final NavigationService navigationService;
    private final AuthorMapper authorMapper;
    private final BookMapper bookMapper;
    private final GenreMapper genreMapper;

    @FXML private TextField searchField;
    @FXML private VBox resultsContainer;
    @FXML private Label statusLabel;

    @FXML private VBox authorsSection;
    @FXML private ListView<AuthorDto> authorsListView;
    @FXML private Label authorsCountLabel;

    @FXML private VBox seriesSection;
    @FXML private ListView<String> seriesListView;
    @FXML private Label seriesCountLabel;

    @FXML private VBox genresSection;
    @FXML private ListView<GenreDto> genresListView;
    @FXML private Label genresCountLabel;

    @FXML private VBox booksSection;
    @FXML private ListView<BookDto> booksListView;
    @FXML private Label booksCountLabel;

    private String lastQuery = "";

    @FXML
    public void initialize() {
        setupListViews();
        setupSearchListener();
        searchField.requestFocus();
    }

    private void setupListViews() {
        // Автори
        authorsListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(AuthorDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getFullName());
            }
        });
        authorsListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                AuthorDto selected = authorsListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToAuthor(AuthorId.fromString(selected.getId()));
                }
            }
        });

        // Серії
        seriesListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });
        seriesListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String selected = seriesListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToSeriesByName(selected);
                }
            }
        });

        // Жанри
        genresListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(GenreDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        genresListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                GenreDto selected = genresListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToGenre(GenreId.fromCode(selected.getCode()));
                }
            }
        });

        // Книги
        booksListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(BookDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTitle() + " — " + item.getAuthorsText());
            }
        });
        booksListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                BookDto selected = booksListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToBook(BookId.fromString(selected.getId()));
                }
            }
        });
    }

    private void setupSearchListener() {
        searchField.textProperty().addListener((obs, old, query) -> {
            if (query != null && !query.isBlank()) {
                performSearch(query);
            } else {
                clearResults();
            }
        });
    }

    public void performSearch(String query) {
        this.lastQuery = query;
        if (query == null || query.isBlank()) {
            clearResults();
            statusLabel.setText("Введіть запит для пошуку");
            return;
        }

        statusLabel.setText("Пошук...");
        new Thread(() -> {
            try {
                Map<String, Object> results = searchService.searchAll(query);
                UiExecutor.runOnUiThread(() -> updateResults(results));
            } catch (Exception e) {
                log.error("Search failed", e);
                UiExecutor.runOnUiThread(() -> statusLabel.setText("Помилка пошуку: " + e.getMessage()));
            }
        }).start();
    }

    @SuppressWarnings("unchecked")
    private void updateResults(Map<String, Object> results) {
        List<AuthorDto> authors = (List<AuthorDto>) results.get("authors");
        List<String> series = (List<String>) results.get("series");
        List<GenreDto> genres = (List<GenreDto>) results.get("genres");
        List<BookDto> books = (List<BookDto>) results.get("books");

        boolean hasResults = false;

        if (authors != null && !authors.isEmpty()) {
            authorsSection.setVisible(true);
            authorsListView.getItems().setAll(authors);
            authorsCountLabel.setText("(" + authors.size() + ")");
            hasResults = true;
        } else {
            authorsSection.setVisible(false);
        }

        if (series != null && !series.isEmpty()) {
            seriesSection.setVisible(true);
            seriesListView.getItems().setAll(series);
            seriesCountLabel.setText("(" + series.size() + ")");
            hasResults = true;
        } else {
            seriesSection.setVisible(false);
        }

        if (genres != null && !genres.isEmpty()) {
            genresSection.setVisible(true);
            genresListView.getItems().setAll(genres);
            genresCountLabel.setText("(" + genres.size() + ")");
            hasResults = true;
        } else {
            genresSection.setVisible(false);
        }

        if (books != null && !books.isEmpty()) {
            booksSection.setVisible(true);
            booksListView.getItems().setAll(books);
            booksCountLabel.setText("(" + books.size() + ")");
            hasResults = true;
        } else {
            booksSection.setVisible(false);
        }

        statusLabel.setText(hasResults ? "Результати пошуку для: \"" + lastQuery + "\"" : "Нічого не знайдено");
    }

    public void clearResults() {
        authorsSection.setVisible(false);
        authorsListView.getItems().clear();
        seriesSection.setVisible(false);
        seriesListView.getItems().clear();
        genresSection.setVisible(false);
        genresListView.getItems().clear();
        booksSection.setVisible(false);
        booksListView.getItems().clear();
        statusLabel.setText("Введіть запит для пошуку");
    }

    public void setInitialQuery(String query) {
        if (query != null && !query.isBlank()) {
            searchField.setText(query);
            performSearch(query);
        } else {
            clearResults();
        }
    }

    public void setResults(List<BookDto> results) {
        if (results != null && !results.isEmpty()) {
            booksSection.setVisible(true);
            booksListView.getItems().setAll(results);
            booksCountLabel.setText("(" + results.size() + ")");
            statusLabel.setText("Знайдено книг: " + results.size());
        } else {
            booksSection.setVisible(false);
            statusLabel.setText("Книг не знайдено");
        }
    }

    @FXML
    public void onSearch() {
        String query = searchField.getText();
        if (query != null && !query.isBlank()) {
            performSearch(query);
        }
    }

    @FXML
    public void onClear() {
        searchField.clear();
        clearResults();
        searchField.requestFocus();
    }
}