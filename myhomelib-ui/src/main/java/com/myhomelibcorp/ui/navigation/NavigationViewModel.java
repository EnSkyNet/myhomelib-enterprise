package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.series.Series;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * ViewModel for navigation state.
 * Stores current filter, mode, and tree items.
 */
@Component
@Getter
public class NavigationViewModel {

    public enum NavigationMode {
        AUTHORS,
        SERIES,
        GENRES,
        COLLECTIONS,
        GROUPS,
        PUBLISHERS
    }

    private final ObjectProperty<NavigationMode> currentMode = new SimpleObjectProperty<>(NavigationMode.AUTHORS);
    private final ObjectProperty<Character> currentFilter = new SimpleObjectProperty<>('*');
    private final StringProperty statusMessage = new SimpleStringProperty("");

    private final ObservableList<Author> filteredAuthors = FXCollections.observableArrayList();
    private final ObservableList<Series> filteredSeries = FXCollections.observableArrayList();

    public void setMode(NavigationMode mode) {
        currentMode.set(mode);
    }

    public NavigationMode getMode() {
        return currentMode.get();
    }

    public void setFilter(char filter) {
        currentFilter.set(filter);
    }

    public char getFilter() {
        return currentFilter.get() != null ? currentFilter.get() : '*';
    }

    public void setStatus(String status) {
        statusMessage.set(status);
    }

    public String getStatus() {
        return statusMessage.get();
    }

    public void setFilteredAuthors(java.util.Collection<Author> authors) {
        filteredAuthors.setAll(authors);
    }

    public void setFilteredSeries(java.util.Collection<Series> series) {
        filteredSeries.setAll(series);
    }

    public void clear() {
        filteredAuthors.clear();
        filteredSeries.clear();
        statusMessage.set("");
    }

    public boolean isAuthorMode() {
        return currentMode.get() == NavigationMode.AUTHORS;
    }

    public boolean isSeriesMode() {
        return currentMode.get() == NavigationMode.SERIES;
    }

    @Override
    public String toString() {
        return "NavigationViewModel{" +
                "mode=" + currentMode.get() +
                ", filter=" + getFilter() +
                ", authors=" + filteredAuthors.size() +
                ", series=" + filteredSeries.size() +
                '}';
    }
}