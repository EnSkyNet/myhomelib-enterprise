package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.ui.presenter.BookSearchPresenter;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchController {

    private final BookSearchPresenter searchPresenter;

    @FXML private TextField searchField;

    @FXML
    public void initialize() {
        // Прив'язуємо поле до властивості query в SearchViewModel
        searchField.textProperty().bindBidirectional(searchPresenter.getQueryProperty());
        searchPresenter.bind();
        searchField.requestFocus();
    }

    @FXML
    private void onAdvancedSearch() {
        // Відкрити діалог розширеного пошуку
    }
}