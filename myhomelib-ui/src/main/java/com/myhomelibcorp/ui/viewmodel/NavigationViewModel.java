package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.GenreDto;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class NavigationViewModel {

    private final ObservableList<AuthorDto> authors = FXCollections.observableArrayList();
    private final ObservableList<String> seriesNames = FXCollections.observableArrayList();
    private final ObservableList<GenreDto> genres = FXCollections.observableArrayList();

    private final ObjectProperty<AuthorDto> selectedAuthor = new SimpleObjectProperty<>();
    private final ObjectProperty<String> selectedSeries = new SimpleObjectProperty<>();
    private final ObjectProperty<GenreDto> selectedGenre = new SimpleObjectProperty<>();

    public ObservableList<AuthorDto> getAuthors() {
        return authors;
    }

    public ObservableList<String> getSeriesNames() {
        return seriesNames;
    }

    public ObservableList<GenreDto> getGenres() {
        return genres;
    }

    public ObjectProperty<AuthorDto> selectedAuthorProperty() {
        return selectedAuthor;
    }

    public ObjectProperty<String> selectedSeriesProperty() {
        return selectedSeries;
    }

    public ObjectProperty<GenreDto> selectedGenreProperty() {
        return selectedGenre;
    }

    public void setAuthors(java.util.List<AuthorDto> list) {
        authors.setAll(list);
    }

    public void setSeriesNames(java.util.List<String> list) {
        seriesNames.setAll(list);
    }

    public void setGenres(java.util.List<GenreDto> list) {
        genres.setAll(list);
    }

    public void selectAuthor(AuthorDto author) {
        selectedAuthor.set(author);
    }

    public void selectSeries(String series) {
        selectedSeries.set(series);
    }

    public void selectGenre(GenreDto genre) {
        selectedGenre.set(genre);
    }

    public void clear() {
        authors.clear();
        seriesNames.clear();
        genres.clear();
        selectedAuthor.set(null);
        selectedSeries.set(null);
        selectedGenre.set(null);
    }
}