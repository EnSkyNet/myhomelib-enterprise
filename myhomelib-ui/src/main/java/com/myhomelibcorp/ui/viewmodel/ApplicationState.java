package com.myhomelibcorp.ui.viewmodel;

import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
@Getter
public class ApplicationState {

    private final DashboardViewModel dashboard = new DashboardViewModel();
    private final SearchViewModel search = new SearchViewModel();
    private final NavigationViewModel navigation = new NavigationViewModel();
    private final BookTableViewModel bookTable = new BookTableViewModel();
    private final BookDetailsViewModel bookDetails = new BookDetailsViewModel();
    private final StatusBarViewModel statusBar = new StatusBarViewModel();
}