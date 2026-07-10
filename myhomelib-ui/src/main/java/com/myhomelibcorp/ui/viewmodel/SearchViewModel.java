package com.myhomelibcorp.ui.viewmodel;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class SearchViewModel {

    private final StringProperty query = new SimpleStringProperty("");
    private final ObservableList<BookViewModel> results = FXCollections.observableArrayList();
    private final BooleanProperty searching = new SimpleBooleanProperty(false);
    private final StringProperty statusMessage = new SimpleStringProperty("");

    public StringProperty queryProperty() {
        return query;
    }

    public ObservableList<BookViewModel> getResults() {
        return results;
    }

    public BooleanProperty searchingProperty() {
        return searching;
    }

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    public void setQuery(String q) {
        query.set(q);
    }

    public void setResults(java.util.List<BookViewModel> list) {
        results.setAll(list);
    }

    public void clearResults() {
        results.clear();
    }

    public void setSearching(boolean flag) {
        searching.set(flag);
    }

    public void setStatusMessage(String msg) {
        statusMessage.set(msg);
    }
}