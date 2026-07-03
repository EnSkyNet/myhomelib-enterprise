package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
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
    private final CoverExtractor coverExtractor;

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
    private ImageView coverImageView;

    // Виправлено: передаємо рівно 11 параметрів
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
    }

    public void showBookDetails(BookDto book) {
        if (book == null) {
            clearDetails();
            return;
        }

        bookDetailsPresenter.showBookDetails(book);

        if (coverImageView != null) {
            coverImageView.setImage(null);

            Task<Image> task = new Task<Image>() {
                @Override
                protected Image call() {
                    try {
                        return coverExtractor.extractCover(book);
                    } catch (Exception e) {
                        log.warn("Failed to extract cover for: {}", book.getTitle(), e);
                        return null;
                    }
                }
            };

            task.setOnSucceeded(e -> {
                Image img = task.getValue();
                if (img != null) {
                    Platform.runLater(() -> coverImageView.setImage(img));
                }
            });

            task.setOnFailed(e -> {
                log.warn("Failed to load cover for: {}", book.getTitle(), task.getException());
            });

            new Thread(task).start();
        }
    }

    public void clearDetails() {
        bookDetailsPresenter.clearDetails();
        if (coverImageView != null) {
            Platform.runLater(() -> coverImageView.setImage(null));
        }
    }
}