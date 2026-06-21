package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DetailsController {

    private final BookDetailsPresenter bookDetailsPresenter;

    private Label detailTitle;
    private Label detailAuthors;
    private Label detailSeries;
    private Label detailGenres;
    private Label detailLanguage;
    private Label detailRate;
    private Label detailProgress;
    private Label detailFile;
    private Label detailFolder;
    private Label detailSize;
    private TextArea detailAnnotation;

    public void setupDetails(
            Label title, Label authors, Label series, Label genres,
            Label language, Label rate, Label progress,
            Label file, Label folder, Label size, TextArea annotation
    ) {
        this.detailTitle = title;
        this.detailAuthors = authors;
        this.detailSeries = series;
        this.detailGenres = genres;
        this.detailLanguage = language;
        this.detailRate = rate;
        this.detailProgress = progress;
        this.detailFile = file;
        this.detailFolder = folder;
        this.detailSize = size;
        this.detailAnnotation = annotation;

        bookDetailsPresenter.bind(
                title, authors, series, genres,
                language, rate, progress,
                file, folder, size, annotation
        );
    }

    public void showBookDetails(BookDto book) {
        bookDetailsPresenter.showBookDetails(book);
    }

    public void clearDetails() {
        bookDetailsPresenter.clearDetails();
    }
}