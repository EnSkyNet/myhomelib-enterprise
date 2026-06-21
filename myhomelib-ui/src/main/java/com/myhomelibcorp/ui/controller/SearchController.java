package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.ui.service.SearchManager;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchController {

    private final SearchManager searchManager;

    public void setupSearch(TextField searchField,
                            ProgressIndicator searchIndicator,
                            TableView<BookDto> tableView,
                            Label statusLabel) {
        searchManager.bindLiveSearch(
                searchField,
                tableView,
                statusLabel,
                searchIndicator
        );
    }
}