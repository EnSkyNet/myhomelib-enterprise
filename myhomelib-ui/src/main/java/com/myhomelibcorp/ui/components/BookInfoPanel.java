package com.myhomelibcorp.ui.components;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.Consumer;

public class BookInfoPanel extends VBox {

    private final ObjectProperty<BookDto> bookProperty = new SimpleObjectProperty<>();

    private final ImageView coverView = new ImageView();
    private final Label titleLabel = new Label();
    private final Hyperlink authorsLink = new Hyperlink();
    private final Hyperlink seriesLink = new Hyperlink();
    private final Hyperlink genresLink = new Hyperlink();
    private final TextArea annotationArea = new TextArea();
    private final ListView<String> metaListView = new ListView<>();

    private Consumer<String> onAuthorClicked;
    private Consumer<String> onSeriesClicked;
    private Consumer<String> onGenreClicked;
    private Consumer<BookDto> onAnnotationClicked;

    public BookInfoPanel() {
        configureUI();
        setupBindings();
        setupEventHandlers();
    }

    private void configureUI() {
        setSpacing(12);
        setPadding(new Insets(15));
        setStyle("-fx-background-color: #ffffff; -fx-border-color: #e0e0e0; -fx-border-width: 1;");

        // Обкладинка
        coverView.setFitWidth(180);
        coverView.setFitHeight(250);
        coverView.setPreserveRatio(true);
        coverView.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.25), 10, 0, 0, 4);");

        HBox coverBox = new HBox(coverView);
        coverBox.setAlignment(Pos.CENTER);

        // Назва
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 17));
        titleLabel.setWrapText(true);

        // Посилання
        VBox linksBox = new VBox(10);
        linksBox.getChildren().addAll(
                createLinkRow("Автори:", authorsLink),
                createLinkRow("Серія:", seriesLink),
                createLinkRow("Жанри:", genresLink)
        );

        // Анотація
        Label annLabel = new Label("Анотація:");
        annLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        annotationArea.setEditable(false);
        annotationArea.setWrapText(true);
        annotationArea.setPrefRowCount(8);
        annotationArea.setStyle("-fx-font-size: 13.5px;");

        // Метадані
        Label metaLabel = new Label("Метадані:");
        metaLabel.setFont(Font.font("System", FontWeight.BOLD, 13));

        metaListView.setPrefHeight(170);

        getChildren().addAll(
                coverBox,
                titleLabel,
                linksBox,
                annLabel,
                annotationArea,
                metaLabel,
                metaListView
        );
    }

    private HBox createLinkRow(String labelText, Hyperlink link) {
        Label label = new Label(labelText);
        label.setPrefWidth(70);
        label.setStyle("-fx-font-weight: bold;");
        HBox row = new HBox(10, label, link);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void setupBindings() {
        bookProperty.addListener((obs, oldBook, newBook) -> {
            if (newBook == null) {
                clear();
            } else {
                updateUI(newBook);
            }
        });
    }

    private void updateUI(BookDto book) {
        titleLabel.setText(book.getTitle() != null ? book.getTitle() : "Без назви");
        authorsLink.setText(book.getAuthorsText() != null ? book.getAuthorsText() : "—");
        seriesLink.setText(book.getSeries() != null && !book.getSeries().isBlank() ? book.getSeries() : "—");
        genresLink.setText(book.getGenresText() != null ? book.getGenresText() : "—");
        annotationArea.setText(book.getAnnotation() != null ? book.getAnnotation() : "");

        metaListView.getItems().setAll(
                "Файл: " + (book.getFileName() != null ? book.getFileName() : "—"),
                "Папка: " + (book.getFolder() != null ? book.getFolder() : "—"),
                "Розмір: " + book.getFileSizeFormatted(),
                "Мова: " + (book.getLanguage() != null ? book.getLanguage() : "—"),
                "Рейтинг: " + book.getRateStars(),
                "Прогрес: " + book.getProgressFormatted(),
                "Додано: " + book.getCreatedAtFormatted(),
                "Оновлено: " + book.getUpdateDateFormatted()
        );
    }

    private void setupEventHandlers() {
        authorsLink.setOnAction(e -> {
            if (onAuthorClicked != null && bookProperty.get() != null) {
                onAuthorClicked.accept(bookProperty.get().getAuthorsText());
            }
        });

        seriesLink.setOnAction(e -> {
            if (onSeriesClicked != null && bookProperty.get() != null) {
                onSeriesClicked.accept(bookProperty.get().getSeries());
            }
        });

        genresLink.setOnAction(e -> {
            if (onGenreClicked != null && bookProperty.get() != null) {
                onGenreClicked.accept(bookProperty.get().getGenresText());
            }
        });

        annotationArea.setOnMouseClicked(e -> {
            if (onAnnotationClicked != null && bookProperty.get() != null) {
                onAnnotationClicked.accept(bookProperty.get());
            }
        });
    }

    public void setBook(BookDto book) {
        bookProperty.set(book);
    }

    public ObjectProperty<BookDto> bookProperty() {
        return bookProperty;
    }

    public void setOnAuthorClicked(Consumer<String> handler) {
        this.onAuthorClicked = handler;
    }

    public void setOnSeriesClicked(Consumer<String> handler) {
        this.onSeriesClicked = handler;
    }

    public void setOnGenreClicked(Consumer<String> handler) {
        this.onGenreClicked = handler;
    }

    public void setOnAnnotationClicked(Consumer<BookDto> handler) {
        this.onAnnotationClicked = handler;
    }

    public void setCover(Image image) {
        coverView.setImage(image);
    }

    public void clear() {
        bookProperty.set(null);
        titleLabel.setText("");
        authorsLink.setText("");
        seriesLink.setText("");
        genresLink.setText("");
        annotationArea.clear();
        metaListView.getItems().clear();
        coverView.setImage(null);
    }
}