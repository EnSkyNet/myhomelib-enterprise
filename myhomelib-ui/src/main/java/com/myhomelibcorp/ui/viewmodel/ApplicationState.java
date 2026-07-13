package com.myhomelibcorp.ui.viewmodel;

import org.springframework.stereotype.Component;

@Component
public class ApplicationState {

    private final DashboardViewModel dashboard = new DashboardViewModel();
    private final SearchViewModel search = new SearchViewModel();
    private final NavigationViewModel navigation = new NavigationViewModel();
    private final BookTableViewModel bookTable = new BookTableViewModel();
    private final BookDetailsViewModel bookDetails = new BookDetailsViewModel();
    private final StatusBarViewModel statusBar = new StatusBarViewModel();

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
}