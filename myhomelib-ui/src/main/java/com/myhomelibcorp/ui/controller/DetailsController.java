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

    // Базові поля
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

    // Нові поля (додані)
    private Label detailReview;
    private Label detailCreated;
    private Label detailKeywords;

    /**
     * Налаштовує всі елементи керування для деталей книги.
     * Викликається з FXML-контролера після завантаження view.
     */
    public void setupDetails(
            Label title, Label authors, Label series, Label genres,
            Label language, Label rate, Label progress,
            Label file, Label folder, Label size, TextArea annotation,
            Label review, Label created, Label keywords) {

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
        this.detailReview = review;
        this.detailCreated = created;
        this.detailKeywords = keywords;

        // Прив'язуємо всі елементи до презентера (14 аргументів)
        bookDetailsPresenter.bind(
                title, authors, series, genres,
                language, rate, progress,
                file, folder, size, annotation,
                review, created, keywords
        );
    }

    public void showBookDetails(BookDto book) {
        bookDetailsPresenter.showBookDetails(book);
    }

    public void clearDetails() {
        bookDetailsPresenter.clearDetails();
    }
}