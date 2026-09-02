package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.ui.table.BookTableController;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@Slf4j
public class ApplicationState {

    private final DashboardViewModel dashboard = new DashboardViewModel();
    private final BookTableViewModel bookTable = new BookTableViewModel();
    private final BookDetailsViewModel bookDetails = new BookDetailsViewModel();
    private final StatusBarViewModel statusBar = new StatusBarViewModel();

    // Посилання на контролер таблиці
    private BookTableController bookTableController;

    private final ObjectProperty<Collection> currentLibraryCollection = new SimpleObjectProperty<>();
    private final ObjectProperty<Group> currentGroup = new SimpleObjectProperty<>();

    // Гетери / сетери
    public DashboardViewModel getDashboard() { return dashboard; }
    public BookTableViewModel getBookTable() { return bookTable; }
    public BookDetailsViewModel getBookDetails() { return bookDetails; }
    public StatusBarViewModel getStatusBar() { return statusBar; }

    public ObjectProperty<Collection> currentLibraryCollectionProperty() { return currentLibraryCollection; }
    public Collection getCurrentLibraryCollection() { return currentLibraryCollection.get(); }
    public void setCurrentLibraryCollection(Collection collection) {
        Collection previous = currentLibraryCollection.get();
        String previousId = previous == null ? null : previous.getId();
        String nextId = collection == null ? null : collection.getId();
        if (!Objects.equals(previousId, nextId)) {
            dashboard.clear();
            bookTable.clear();
            bookDetails.clear();
            statusBar.setStatistics(null);
            currentGroup.set(null);
        }
        currentLibraryCollection.set(collection);
    }

    public ObjectProperty<Group> currentGroupProperty() { return currentGroup; }
    public Group getCurrentGroup() { return currentGroup.get(); }
    public void setCurrentGroup(Group group) { currentGroup.set(group); }

    // Методи для роботи з контролером таблиці
    public BookTableController getBookTableController() { return bookTableController; }
    public void setBookTableController(BookTableController controller) {
        this.bookTableController = controller;
        log.info("ApplicationState: BookTableController встановлено.");
    }
}