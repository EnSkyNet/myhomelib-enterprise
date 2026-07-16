package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.springframework.stereotype.Component;

@Component
public class ApplicationState {

    private final DashboardViewModel dashboard = new DashboardViewModel();
    private final SearchViewModel search = new SearchViewModel();
    private final NavigationViewModel navigation = new NavigationViewModel();
    private final BookTableViewModel bookTable = new BookTableViewModel();
    private final BookDetailsViewModel bookDetails = new BookDetailsViewModel();
    private final StatusBarViewModel statusBar = new StatusBarViewModel();

    // Поточна колекція (база даних)
    private final ObjectProperty<Collection> currentLibraryCollection = new SimpleObjectProperty<>();

    // Поточна група (список книг)
    private final ObjectProperty<Group> currentGroup = new SimpleObjectProperty<>();

    // Геттери / сеттери

    public DashboardViewModel getDashboard() {
        return dashboard;
    }

    public SearchViewModel getSearch() {
        return search;
    }

    public NavigationViewModel getNavigation() {
        return navigation;
    }

    public BookTableViewModel getBookTable() {
        return bookTable;
    }

    public BookDetailsViewModel getBookDetails() {
        return bookDetails;
    }

    public StatusBarViewModel getStatusBar() {
        return statusBar;
    }

    public ObjectProperty<Collection> currentLibraryCollectionProperty() {
        return currentLibraryCollection;
    }

    public Collection getCurrentLibraryCollection() {
        return currentLibraryCollection.get();
    }

    public void setCurrentLibraryCollection(Collection collection) {
        currentLibraryCollection.set(collection);
    }

    public ObjectProperty<Group> currentGroupProperty() {
        return currentGroup;
    }

    public Group getCurrentGroup() {
        return currentGroup.get();
    }

    public void setCurrentGroup(Group group) {
        currentGroup.set(group);
    }
}