package com.myhomelibcorp.ui.presentation;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import org.springframework.stereotype.Component;

@Component
public class BookDetailsPresenter {

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

    // +++ НОВІ ПОЛЯ +++
    private Label detailReview;
    private Label detailCreated;
    private Label detailKeywords;

    // +++ ОНОВЛЕНИЙ МЕТОД BIND +++
    public void bind(Label title, Label authors, Label series, Label genres,
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

        // +++ НОВІ +++
        this.detailReview = review;
        this.detailCreated = created;
        this.detailKeywords = keywords;
    }

    public void showBookDetails(BookDto book) {
        if (book == null) {
            clearDetails();
            return;
        }

        detailTitle.setText(book.getTitle() != null ? book.getTitle() : "Без назви");
        detailAuthors.setText("Автори: " + (book.getAuthorsText() != null ? book.getAuthorsText() : ""));
        detailSeries.setText("Серія: " + (book.getSeries() != null ? book.getSeries() : ""));
        detailGenres.setText("Жанри: " + (book.getGenresText() != null ? book.getGenresText() : ""));
        detailLanguage.setText("Мова: " + (book.getLanguage() != null ? book.getLanguage() : ""));
        detailRate.setText("Рейтинг: " + book.getRate());
        detailProgress.setText("Прогрес: " + book.getProgress() + "%");
        detailFile.setText("Файл: " + (book.getFileName() != null ? book.getFileName() : ""));
        detailFolder.setText("Папка: " + (book.getFolder() != null ? book.getFolder() : ""));
        detailSize.setText("Розмір: " + book.getFileSizeFormatted());
        detailAnnotation.setText(book.getAnnotation() != null ? book.getAnnotation() : "");

        // +++ НОВІ ПОЛЯ +++
        detailReview.setText("Рецензія: " + (book.getReview() != null ? book.getReview() : ""));
        detailCreated.setText("Додано: " + book.getCreatedAtFormatted());
        detailKeywords.setText("Ключові слова: " + (book.getKeywords() != null ? book.getKeywords() : ""));
    }

    public void clearDetails() {
        detailTitle.setText("Назва");
        detailAuthors.setText("Автори");
        detailSeries.setText("Серія");
        detailGenres.setText("Жанри");
        detailLanguage.setText("Мова");
        detailRate.setText("Рейтинг: 0");
        detailProgress.setText("Прогрес: 0%");
        detailFile.setText("Файл: ");
        detailFolder.setText("Папка: ");
        detailSize.setText("Розмір: ");
        detailAnnotation.setText("");

        // +++ НОВІ +++
        detailReview.setText("Рецензія:");
        detailCreated.setText("Додано:");
        detailKeywords.setText("Ключові слова:");
    }
}