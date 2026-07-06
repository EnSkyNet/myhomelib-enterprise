package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DetailsController {

    private final BookDetailsPresenter bookDetailsPresenter;
    private final CoverPresenter coverPresenter;

    private Label detailTitle, detailAuthors, detailSeries, detailGenres, detailLanguage, detailRate, detailProgress, detailFile, detailFolder, detailSize;
    private TextArea detailAnnotation;
    private ImageView coverImageView;

    public void setupDetails(
            Label title, Label authors, Label series, Label genres,
            Label language, Label rate, Label progress,
            Label file, Label folder, Label size,
            TextArea annotation,
            ImageView coverImageView
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
        this.coverImageView = coverImageView;

        bookDetailsPresenter.bind(
                title, authors, series, genres,
                language, rate, progress,
                file, folder, size, annotation
        );
        coverPresenter.bind(coverImageView);
    }

    public void showBookDetails(BookViewModel book) {
        if (book == null) {
            clearDetails();
            return;
        }
        bookDetailsPresenter.showBookDetails(book);
        coverPresenter.showCover(book);
    }

    public void clearDetails() {
        bookDetailsPresenter.clearDetails();
        coverPresenter.clearCover();
    }
}