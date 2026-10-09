package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.dto.NextInSeriesItemDto;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class DashboardViewModel {

    private final ObjectProperty<BookDto> continueReading = new SimpleObjectProperty<>();
    private final ObservableList<BookDto> recentBooks = FXCollections.observableArrayList();
    private final ObservableList<BookDto> newBooks = FXCollections.observableArrayList();
    private final ObservableList<NextInSeriesItemDto> nextInSeries = FXCollections.observableArrayList();
    private final ObservableList<AuthorDto> favoriteAuthors = FXCollections.observableArrayList(); // ЗМІНЕНО
    private final ObjectProperty<LibraryStatistics> statistics = new SimpleObjectProperty<>();

    public ObjectProperty<BookDto> continueReadingProperty() {
        return continueReading;
    }

    public ObservableList<BookDto> getRecentBooks() {
        return recentBooks;
    }

    public ObservableList<BookDto> getNewBooks() {
        return newBooks;
    }

    public ObservableList<NextInSeriesItemDto> getNextInSeries() { return nextInSeries; }

    public ObservableList<AuthorDto> getFavoriteAuthors() { // ЗМІНЕНО
        return favoriteAuthors;
    }

    public ObjectProperty<LibraryStatistics> statisticsProperty() {
        return statistics;
    }

    public void setContinueReading(BookDto book) {
        continueReading.set(book);
    }

    public void setRecentBooks(java.util.List<BookDto> books) {
        recentBooks.setAll(books);
    }

    public void setNewBooks(java.util.List<BookDto> books) {
        newBooks.setAll(books);
    }

    public void setNextInSeries(java.util.List<NextInSeriesItemDto> items) { nextInSeries.setAll(items == null ? java.util.List.of() : items); }

    public void setFavoriteAuthors(java.util.List<AuthorDto> authors) { // ЗМІНЕНО
        favoriteAuthors.setAll(authors);
    }

    public void setStatistics(LibraryStatistics stats) {
        statistics.set(stats);
    }

    public BookDto getContinueReading() {
        return continueReading.get();
    }

    public LibraryStatistics getStatistics() {
        return statistics.get();
    }

    public void clear() {
        continueReading.set(null);
        recentBooks.clear();
        newBooks.clear();
        nextInSeries.clear();
        favoriteAuthors.clear();
        statistics.set(null);
    }
}